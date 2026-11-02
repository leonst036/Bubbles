from datetime import datetime
from zoneinfo import ZoneInfo
from typing import Any


def register_time_tools(mcp: Any) -> None:
    @mcp.tool()
    def get_current_time(timezone: str = "Europe/Berlin") -> str:
        """Return the current date and time for a given timezone (defaults to Europe/Berlin)."""
        try:
            if timezone.lower() in ("local", "auto"):
                tz = datetime.now().astimezone().tzinfo
                timezone = "local"
            else:
                tz = ZoneInfo(timezone)
        except Exception:
            tz = ZoneInfo("UTC")
            timezone = "UTC (fallback)"

        now = datetime.now(tz)
        return f"Current time ({timezone}): {now.strftime('%Y-%m-%d %H:%M:%S %Z')}"
