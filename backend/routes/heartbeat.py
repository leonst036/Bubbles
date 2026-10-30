from fastapi import APIRouter

router = APIRouter(tags=["heartbeat"])


@router.get("/heartbeat")
async def get_heartbeat():
    # Return simple status response
    return {"status": "ok"}
