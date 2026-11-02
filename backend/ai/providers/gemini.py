import asyncio
import os
from pathlib import Path
from typing import Set
from google import genai
from google.genai import types

# Load .env file if GEMINI_API_KEY is not already set
env_path = Path(__file__).resolve().parents[3] / ".env"
if env_path.exists() and "GEMINI_API_KEY" not in os.environ:
    with open(env_path) as f:
        for line in f:
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                key, val = stripped.split("=", 1)
                os.environ[key.strip()] = val.strip("\"' ")

client = genai.Client()

INPUT_SAMPLE_RATE = 16000
OUTPUT_SAMPLE_RATE = 24000
CHANNELS = 1
CHUNK_SIZE = 1024


def load_system_prompt() -> str:
    promts_dir = Path(__file__).resolve().parents[1] / "promts"
    for filename in ("SystemPromt.md", "SystemPromt.txt"):
        path = promts_dir / filename
        if path.exists():
            return path.read_text(encoding="utf-8")
    return "You are Bubble."


def build_live_config() -> types.LiveConnectConfig:
    system_prompt = load_system_prompt()
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
    )


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

    async def _receive_responses(self, session):
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

    async def _session_loop(self):
        while True:
            try:
                config = build_live_config()
                print("Connecting to Bubbles (Gemini 3.8 Live Extended Thinking)...")
                async with client.aio.live.connect(
                    model="gemini-3.8-live-extended-thinking", config=config
                ) as session:
                    self._session = session
                    self._connected_event.set()
                    print("Connected to Gemini Live session.")
                    await self._receive_responses(session)
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
        # Send brief silence so server-side VAD detects end of speech after push-to-talk release
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


async def send_mic_audio(session, mic_stream):
    # Stream microphone audio to Gemini Live
    try:
        while True:
            data = await asyncio.to_thread(mic_stream.read, CHUNK_SIZE, False)
            await session.send_realtime_input(
                audio=types.Blob(data=data, mime_type="audio/pcm;rate=16000")
            )
    except asyncio.CancelledError:
        pass


async def play_audio_stream(speaker_stream, audio_queue: asyncio.Queue):
    # Play received audio chunks through speaker
    try:
        while True:
            chunk = await audio_queue.get()
            if chunk is None:
                break
            await asyncio.to_thread(speaker_stream.write, chunk)
            audio_queue.task_done()
    except asyncio.CancelledError:
        pass


async def receive_responses(session, audio_queue: asyncio.Queue):
    # Continuously receive audio and transcriptions across all turns
    try:
        while True:
            async for response in session.receive():
                server_content = response.server_content
                if server_content is not None:
                    if server_content.interrupted:
                        while not audio_queue.empty():
                            try:
                                audio_queue.get_nowait()
                                audio_queue.task_done()
                            except asyncio.QueueEmpty:
                                break
                        print("\n[Interrupted]")

                    if server_content.model_turn is not None:
                        for part in server_content.model_turn.parts:
                            if part.inline_data and part.inline_data.data:
                                await audio_queue.put(part.inline_data.data)

                    if server_content.output_transcription:
                        print(
                            server_content.output_transcription.text,
                            end="",
                            flush=True,
                        )

                    if server_content.turn_complete:
                        print("\n\n--- (Listening...) ---")
    except asyncio.CancelledError:
        pass


async def main():
    import pyaudio

    p = pyaudio.PyAudio()

    mic_stream = p.open(
        format=pyaudio.paInt16,
        channels=CHANNELS,
        rate=INPUT_SAMPLE_RATE,
        input=True,
        frames_per_buffer=CHUNK_SIZE,
    )

    speaker_stream = p.open(
        format=pyaudio.paInt16,
        channels=CHANNELS,
        rate=OUTPUT_SAMPLE_RATE,
        output=True,
    )

    audio_queue = asyncio.Queue()
    config = build_live_config()

    print("Connecting to Bubbles (Gemini 3.8 Live Extended Thinking)...")
    print("Start speaking into your microphone! Press Ctrl+C to stop.\n")

    try:
        async with client.aio.live.connect(
            model="gemini-3.8-live-extended-thinking", config=config
        ) as session:
            mic_task = asyncio.create_task(send_mic_audio(session, mic_stream))
            playback_task = asyncio.create_task(
                play_audio_stream(speaker_stream, audio_queue)
            )
            receive_task = asyncio.create_task(
                receive_responses(session, audio_queue)
            )

            await asyncio.gather(mic_task, playback_task, receive_task)
    except asyncio.CancelledError:
        pass
    finally:
        mic_stream.stop_stream()
        mic_stream.close()
        speaker_stream.stop_stream()
        speaker_stream.close()
        p.terminate()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nDisconnected.")
