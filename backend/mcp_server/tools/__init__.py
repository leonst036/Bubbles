from typing import Any
from .time_tool import register_time_tools
from .test_tool import register_test_tools


def register_all_tools(mcp: Any) -> None:
    # Register all modular tools
    register_time_tools(mcp)
    register_test_tools(mcp)
