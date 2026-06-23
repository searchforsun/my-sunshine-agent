#!/usr/bin/env python3
"""示例：规范化上游注入的待办 JSON。"""

from __future__ import annotations

import json
import sys
from typing import Any


def normalize_todos(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for item in raw:
        out.append({
            "id": str(item.get("id", "")).strip(),
            "title": str(item.get("title", "")).strip(),
            "amount": float(item.get("amount", 0) or 0),
            "status": str(item.get("status", "pending")).strip(),
        })
    return out


def main() -> int:
    payload = json.load(sys.stdin)
    todos = payload.get("todos") or []
    print(json.dumps({"todos": normalize_todos(todos)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
