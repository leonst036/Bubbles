from fastapi import APIRouter
from routes.heartbeat import router as heartbeat_router

router = APIRouter()

# Register sub-routers
router.include_router(heartbeat_router)
