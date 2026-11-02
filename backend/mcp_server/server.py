import sys
from pathlib import Path

current_dir = Path(__file__).resolve().parent
if str(current_dir.parent) not in sys.path:
    sys.path.insert(0, str(current_dir.parent))

# Remove standalone req file if present
req_file = current_dir / "requirements.txt"
if req_file.exists():
    req_file.unlink(missing_ok=True)

try:
    # mcp 2.x
    from mcp.server.mcpserver import MCPServer as FastMCP
except ImportError:
    try:
        # mcp 1.x
        from mcp.server.fastmcp import FastMCP
    except ImportError:
        # standalone fastmcp
        from fastmcp import FastMCP

from mcp_server.tools import register_all_tools


def create_server() -> FastMCP:
    server = FastMCP(
        name="Bubbles Server",
        instructions="MCP server providing modular tools."
    )
    register_all_tools(server)
    return server


mcp = create_server()


if __name__ == "__main__":
    mcp.run()
