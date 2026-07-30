#!/usr/bin/env python3
"""
A2A 外部智能体 Mock Server — 用于测试智能体管理的外部 Agent 接入流程。

提供：
  GET  /.well-known/agent-card.json  — Agent Card 元信息
  POST /tasks/sendSubscribe           — A2A SSE 流式响应

用法：
  python3 scripts/mock_a2a_agent.py [--port 9876]
"""

import argparse
import json
import time
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler

AGENT_CARD = {
    "name": "Mock 财务分析智能体",
    "description": "模拟外部财务分析服务，用于测试 A2A 接入流程",
    "version": "1.0.0",
    "skills": [
        {"name": "financial-analysis", "description": "财务报表分析"},
        {"name": "expense-audit", "description": "费用合规审计"},
    ],
    "defaultInputModes": ["text/plain"],
    "defaultOutputModes": ["text/plain"],
    "capabilities": {"streaming": True},
    "supportedInterfaces": [
        {"url": "http://127.0.0.1:{port}/tasks/sendSubscribe", "protocol": "A2A/1.0"}
    ],
}

class A2AHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        msg = fmt % args
        if "200" in msg or "OK" in msg or "POST" in msg:
            print(f"[A2A] {msg}", flush=True)
        else:
            sys.stderr.write(f"[A2A] {msg}\n")

    def do_GET(self):
        if self.path.startswith("/.well-known/agent-card"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            card = json.loads(json.dumps(AGENT_CARD).replace("{port}", str(self.server.server_port)))
            self.wfile.write(json.dumps(card, ensure_ascii=False).encode())
            print("[A2A] GET /.well-known/agent-card.json → 200", flush=True)
        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'{"error":"not found"}')

    def do_POST(self):
        if self.path == "/tasks/sendSubscribe":
            content_len = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_len).decode("utf-8") if content_len > 0 else "{}"
            print(f"[A2A] POST /tasks/sendSubscribe body={body[:120]}...", flush=True)

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()

            # SSE stream: task + status + artifact + done
            events = [
                'data: {"kind":"task","id":"t-001"}\n\n',
                'data: {"kind":"status-update","status":{"state":"working","message":"正在分析…"}}\n\n',
            ]
            # 模拟流式正文输出
            answer = (
                "根据提供的财务数据，本次分析结果如下：\n\n"
                "1. **收入结构**：主营业务收入占比 85%，符合行业常规。\n"
                "2. **费用合理性**：差旅费同比增长 12%，但人均差旅持平，属业务扩展的正常增长。\n"
                "3. **风险提示**：应收账款周转天数由 45 天升至 62 天，建议关注回款周期。\n\n"
                "整体财务健康度评分：B+（良好），无重大合规风险。"
            )
            # 每 3~5 字一个 chunk
            i = 0
            while i < len(answer):
                chunk = answer[i:i+4]
                events.append(
                    f'data: {{"kind":"artifact-update","artifact":{{"parts":[{{"kind":"text","text":"{chunk}"}}]}}}}\n\n'
                )
                i += len(chunk)

            events.append('data: {"kind":"status-update","status":{"state":"completed","message":"分析完成"}}\n\n')
            events.append('data: [DONE]\n\n')

            for evt in events:
                self.wfile.write(evt.encode())
                self.wfile.flush()
                time.sleep(0.03)  # ~30ms per chunk for realistic streaming
            print("[A2A] POST /tasks/sendSubscribe → stream done", flush=True)
        else:
            self.send_response(404)
            self.end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type,Authorization")
        self.end_headers()


def main():
    parser = argparse.ArgumentParser(description="A2A Mock Agent Server")
    parser.add_argument("--port", type=int, default=9876, help="监听端口 (默认 9876)")
    args = parser.parse_args()

    port = args.port
    server = HTTPServer(("0.0.0.0", port), A2AHandler)
    server.server_port = port

    print(f"[A2A] Mock 外部智能体启动: http://127.0.0.1:{port}", flush=True)
    print(f"[A2A] Agent Card:       http://127.0.0.1:{port}/.well-known/agent-card.json", flush=True)
    print("[A2A] 按 Ctrl+C 停止", flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[A2A] 已停止", flush=True)
        server.shutdown()


if __name__ == "__main__":
    main()
