import sqlite3
from pathlib import Path


class SQLiteManager:
    def __init__(self, db_name: str = "bubbles.db"):
        self.db_dir = Path.home() / ".bubbles"
        self.db_dir.mkdir(parents=True, exist_ok=True)
        self.db_path = self.db_dir / db_name
        self.conn = sqlite3.connect(self.db_path)

    def get_connection(self) -> sqlite3.Connection:
        return self.conn

    def close(self):
        if self.conn:
            self.conn.close()
