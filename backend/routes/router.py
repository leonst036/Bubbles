from fastapi import APIRouter
from routes.heartbeat import router as heartbeat_router
from routes.stream import router as stream_router

router = APIRouter()

# Register sub-routers
router.include_router(heartbeat_router)
router.include_router(stream_router)
