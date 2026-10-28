from fastapi import APIRouter
from .utills.heartbeat import heartbeat

router = APIRouter()

router.add_api_route("/heartbeat", heartbeat, methods=["GET"])