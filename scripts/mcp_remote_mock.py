#!/usr/bin/env python3
"""模拟远程 MCP（HTTP JSON-RPC），供 tool-manager transport=sse 探测/调用验收。"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {
        "name": "get_weather",
        "description": "查询指定城市天气（Demo）",
        "inputSchema": {
            "type": "object",
            "required": ["city"],
            "properties": {
                "city": {"type": "string", "description": "城市名，如 北京"},
            },
        },
    },
    {
        "name": "search_docs",
        "description": "按关键词搜索文档（Demo）",
        "inputSchema": {
            "type": "object",
            "required": ["query"],
            "properties": {
                "query": {"type": "string", "description": "搜索关键词"},
            },
        },
    },
]


def build_response(body: dict) -> dict:
    method = body.get("method")
    req_id = body.get("id")
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "demo-remote-mcp", "version": "1.0.0"},
            },
        }
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        text = "（Demo）已执行"
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {"content": [{"type": "text", "text": text}]},
        }
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": f"method not found: {method}"},
    }


class McpRemoteHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path in ("/", "/health"):
            self._write_json(200, {"status": "ok", "service": "demo-remote-mcp"})
            return
        self.send_error(404)

    def do_POST(self) -> None:
        if self.path != "/mcp":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length > 0 else b"{}"
        try:
            body = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            self._write_json(400, {"error": "invalid json"})
            return
        self._write_json(200, build_response(body))

    def _write_json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt: str, *args) -> None:
        print(f"[mcp-remote-mock] {self.address_string()} {fmt % args}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Demo remote MCP HTTP server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8720)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), McpRemoteHandler)
    print(f"[mcp-remote-mock] listening http://{args.host}:{args.port}/mcp")
    server.serve_forever()


if __name__ == "__main__":
    main()
