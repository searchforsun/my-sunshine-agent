#!/usr/bin/env python3
"""本地 stdio MCP Demo（JSON-RPC 按行），供 tool-manager transport=stdio 探测/调用。"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone

TOOLS = [
    {
        "name": "get_time",
        "description": "返回当前 UTC 时间（Demo）",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "echo_text",
        "description": "回显输入文本（Demo）",
        "inputSchema": {
            "type": "object",
            "required": ["text"],
            "properties": {"text": {"type": "string", "description": "要回显的文本"}},
        },
    },
]


def write_message(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle(body: dict) -> None:
    method = body.get("method")
    req_id = body.get("id")
    if method == "initialize":
        write_message(
            {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "demo-stdio-mcp", "version": "1.0.0"},
                },
            }
        )
        return
    if method == "notifications/initialized":
        return
    if method == "tools/list":
        write_message({"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}})
        return
    if method == "tools/call":
        params = body.get("params") or {}
        name = params.get("name", "")
        arguments = params.get("arguments") or {}
        if name == "get_time":
            text = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        elif name == "echo_text":
            text = str(arguments.get("text", ""))
        else:
            write_message(
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": f"unknown tool: {name}"},
                }
            )
            return
        write_message(
            {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": text}]},
            }
        )
        return
    if req_id is not None:
        write_message(
            {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32601, "message": f"method not found: {method}"},
            }
        )


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            body = json.loads(line)
        except json.JSONDecodeError:
            continue
        handle(body)


if __name__ == "__main__":
    main()
