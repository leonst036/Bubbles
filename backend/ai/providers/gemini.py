import asyncio
import os
from pathlib import Path
from google import genai
from google.genai import types
import pyaudio

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
                    # Clear queued audio if model was interrupted by user speech
                    if server_content.interrupted:
                        while not audio_queue.empty():
                            try:
                                audio_queue.get_nowait()
                                audio_queue.task_done()
                            except asyncio.QueueEmpty:
                                break
                        print("\n[Interrupted]")

                    # Queue audio data for playback
                    if server_content.model_turn is not None:
                        for part in server_content.model_turn.parts:
                            if part.inline_data and part.inline_data.data:
                                await audio_queue.put(part.inline_data.data)

                    # Print real-time transcription
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

    config = types.LiveConnectConfig(
        response_modalities=[types.Modality.AUDIO],
        thinking_config=types.ThinkingConfig(
            thinking_level=types.ThinkingLevel.HIGH,
            include_thoughts=True,
        ),
        output_audio_transcription=types.AudioTranscriptionConfig(),
        system_instruction=types.Content(
            parts=[
                types.Part.from_text(
                    text="You are Bubbles, a cute, playful companion. Keep your spoken responses concise and natural."
                )
            ]
        ),
    )

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
