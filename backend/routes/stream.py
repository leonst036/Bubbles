from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse
from ai.providers.gemini import gemini_manager, OUTPUT_SAMPLE_RATE

router = APIRouter(tags=["stream"])


@router.post("/api/stream/input")
async def stream_input(request: Request):
    # Stream incoming raw 16kHz 16-bit mono PCM audio to Gemini Live
    remainder = b""
    try:
        async for chunk in request.stream():
            if not chunk:
                continue
            data = remainder + chunk
            # Ensure even byte count for 16-bit PCM frames
            even_len = len(data) & ~1
            if even_len > 0:
                await gemini_manager.send_audio(data[:even_len])
            remainder = data[even_len:]
    finally:
        await gemini_manager.end_audio_turn()

    return {"status": "ok"}


@router.get("/api/stream/output")
async def stream_output():
    # Stream outgoing raw 24kHz 16-bit mono PCM audio from Gemini Live
    return StreamingResponse(
        gemini_manager.stream_output(),
        media_type=f"audio/pcm;rate={OUTPUT_SAMPLE_RATE}",
    )
