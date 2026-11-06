import asyncio
import contextlib
import os
import sys
from pathlib import Path
from typing import Set
from google import genai
from google.genai import types

try:
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    MCP_AVAILABLE = True
except ImportError:
    MCP_AVAILABLE = False

# Load .env file
env_path = Path(__file__).resolve().parents[3] / ".env"
if env_path.exists():
    with open(env_path) as f:
        for line in f:
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                key, val = stripped.split("=", 1)
                os.environ[key.strip()] = val.strip("\"' ")

client = genai.Client()

INPUT_SAMPLE_RATE = 16000
OUTPUT_SAMPLE_RATE = 24000
CHUNK_SIZE = 1024


def load_system_prompt() -> str:
    promts_dir = Path(__file__).resolve().parents[1] / "promts"
    for filename in ("SystemPromt.md", "SystemPromt.txt"):
        path = promts_dir / filename
        if path.exists():
            return path.read_text(encoding="utf-8")
    return "You are Bubble."


def build_live_config(tools: list | None = None, tool_descriptions: str = "") -> types.LiveConnectConfig:
    system_prompt = load_system_prompt()
    if tool_descriptions:
        system_prompt += (
            f"\n\n## Tools & Capabilities\n"
            f"You have access to these external MCP tools: {tool_descriptions}.\n"
            f"- If the user asks you to test or check your tools, call `test_tool` or `get_current_time`.\n"
            f"- When asked what tools you have, mention them.\n"
            f"- Always use the result returned from a tool call to answer the user.\n"
            f"- If a tool encounters an error, state the exact error message clearly to the user."
        )

    return types.LiveConnectConfig(
        response_modalities=[types.Modality.AUDIO],
        thinking_config=types.ThinkingConfig(
            thinking_level=types.ThinkingLevel.HIGH,
            include_thoughts=True,
        ),
        output_audio_transcription=types.AudioTranscriptionConfig(),
        system_instruction=types.Content(
            parts=[types.Part.from_text(text=system_prompt)]
        ),
        tools=tools,
    )


@contextlib.asynccontextmanager
async def connect_mcp_session():
    # Start and connect to MCP server
    if not MCP_AVAILABLE:
        print("[MCP] mcp package not installed, running without MCP tools.")
        yield None
        return

    backend_dir = Path(__file__).resolve().parents[2]
    server_params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "mcp_server.server"],
        cwd=str(backend_dir),
    )

    try:
        async with stdio_client(server_params) as (read_stream, write_stream):
            async with ClientSession(read_stream, write_stream) as mcp_session:
                await mcp_session.initialize()
                print("[MCP] Connected to FastMCP server.")
                yield mcp_session
    except Exception as e:
        print(f"[MCP] Failed to connect to MCP server: {e}")
        yield None


async def execute_tool_calls(session, mcp_session, tool_call):
    # Execute MCP tools
    function_responses = []
    for call in tool_call.function_calls:
        print(f"\n>>> [MCP Tool Call] Tool: '{call.name}' | Args: {call.args} | ID: {call.id}")
        is_error = False
        try:
            call_args = dict(call.args) if call.args else {}
            result = await mcp_session.call_tool(call.name, arguments=call_args)
            output_parts = [
                content.text
                for content in result.content
                if getattr(content, "type", "") == "text"
            ]
            if not output_parts and getattr(result, "structured_content", None):
                output_parts = [str(result.structured_content)]
            output = "\n".join(output_parts) if output_parts else "Success"
            print(f">>> [MCP Tool Result] {output}")
        except Exception as e:
            is_error = True
            output = f"Error executing tool '{call.name}': {e}"
            print(f">>> [MCP Tool Error] {output}")

        response_payload = {
            "result": output,
            "output": output,
        }
        if is_error:
            response_payload["error"] = output

        function_responses.append(
            types.FunctionResponse(
                name=call.name,
                id=call.id,
                response=response_payload,
            )
        )

    try:
        await session.send_tool_response(function_responses=function_responses)
        print(">>> [MCP Tool Response] Sent response to Gemini Live.")
        # Advance server VAD audio clock
        silence = b"\x00" * (CHUNK_SIZE * 4)
        await session.send_realtime_input(
            audio=types.Blob(
                data=silence,
                mime_type=f"audio/pcm;rate={INPUT_SAMPLE_RATE}",
            )
        )
        await session.send_realtime_input(audio_stream_end=True)
    except Exception as e:
        print(f">>> [MCP Tool Response Error] Failed to send tool response: {e}")


class GeminiLiveManager:
    def __init__(self):
        self._session = None
        self._session_task: asyncio.Task | None = None
        self._connected_event = asyncio.Event()
        self._output_queues: Set[asyncio.Queue] = set()
        self._lock = asyncio.Lock()

    async def ensure_running(self):
        async with self._lock:
            if self._session_task is None or self._session_task.done():
                self._connected_event.clear()
                self._session_task = asyncio.create_task(self._session_loop())
        await self._connected_event.wait()

    def _clear_output_queues(self):
        for q in list(self._output_queues):
            while not q.empty():
                try:
                    q.get_nowait()
                    q.task_done()
                except asyncio.QueueEmpty:
                    break

    async def _broadcast_audio(self, data: bytes):
        for q in list(self._output_queues):
            await q.put(data)

    async def _receive_responses(self, session, mcp_session=None):
        while True:
            async for response in session.receive():
                server_content = response.server_content
                if server_content is not None:
                    if server_content.interrupted:
                        self._clear_output_queues()
                        print("\n[Interrupted]")

                    if server_content.model_turn is not None:
                        for part in server_content.model_turn.parts:
                            if part.inline_data and part.inline_data.data:
                                await self._broadcast_audio(part.inline_data.data)

                    if server_content.output_transcription:
                        print(
                            server_content.output_transcription.text,
                            end="",
                            flush=True,
                        )

                    if server_content.turn_complete:
                        print("\n\n--- (Listening...) ---")

                # Handle MCP tool calls
                if response.tool_call is not None and mcp_session is not None:
                    await execute_tool_calls(session, mcp_session, response.tool_call)

    async def _session_loop(self):
        while True:
            try:
                async with connect_mcp_session() as mcp_session:
                    tools = None
                    tool_desc = ""

                    if mcp_session is not None:
                        tools_result = await mcp_session.list_tools()
                        print(f"[MCP] Loaded {len(tools_result.tools)} tool(s): {[t.name for t in tools_result.tools]}")
                        try:
                            from google.genai._mcp_utils import mcp_to_gemini_tools
                            tools = mcp_to_gemini_tools(tools_result.tools)
                        except Exception:
                            tools = tools_result.tools

                        tool_desc = ", ".join(
                            f"'{t.name}' ({t.description or 'utility tool'})" for t in tools_result.tools
                        )

                    config = build_live_config(tools=tools, tool_descriptions=tool_desc)

                    print("Connecting to Bubbles (Gemini 3.8 Live Extended Thinking)...")
                    async with client.aio.live.connect(
                        model="gemini-3.8-live-extended-thinking", config=config
                    ) as session:
                        self._session = session
                        self._connected_event.set()
                        print("Connected to Gemini Live session.")
                        await self._receive_responses(session, mcp_session=mcp_session)
            except asyncio.CancelledError:
                break
            except Exception as e:
                print(f"Gemini Live session error: {e}")
            finally:
                self._session = None
                self._connected_event.clear()
            await asyncio.sleep(1)

    async def send_audio(self, pcm_bytes: bytes):
        if not pcm_bytes:
            return
        await self.ensure_running()
        if self._session is not None:
            await self._session.send_realtime_input(
                audio=types.Blob(
                    data=pcm_bytes,
                    mime_type=f"audio/pcm;rate={INPUT_SAMPLE_RATE}",
                )
            )

    async def end_audio_turn(self):
        if self._session is None:
            return
        silence_chunk = b"\x00" * (CHUNK_SIZE * 2)
        for _ in range(8):
            if self._session is None:
                break
            await self._session.send_realtime_input(
                audio=types.Blob(
                    data=silence_chunk,
                    mime_type=f"audio/pcm;rate={INPUT_SAMPLE_RATE}",
                )
            )
            await asyncio.sleep(0.04)
        try:
            await self._session.send_realtime_input(audio_stream_end=True)
        except Exception as e:
            print(f"[Audio] End audio stream notice: {e}")

    async def stream_output(self):
        await self.ensure_running()
        queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._output_queues.add(queue)
        try:
            while True:
                chunk = await queue.get()
                yield chunk
                queue.task_done()
        finally:
            self._output_queues.discard(queue)


gemini_manager = GeminiLiveManager()
