from typing import Any


def register_test_tools(mcp: Any) -> None:
    @mcp.tool()
    def test_tool() -> str:
        """Test tool to verify MCP integration and tool execution."""
        return "MCP test successful! The server and tools are functioning properly."
