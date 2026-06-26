#!/usr/bin/env python3
"""3.3 HITL PR-1 Live 验收：写工具 SSE confirmation + confirm-tool 恢复。"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import threading
import time
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))


def wait_port(port: int, timeout: float = 90.0) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2)
            s.close()
            return True
        except OSError:
            time.sleep(2)
    return False


def start_services() -> None:
    from sunshine_lib import start_java_detached

    services = [
        ("tool-manager", "tool-manager", "sunshine-tool-manager", 8210),
        ("orchestrator", "orchestrator", "sunshine-orchestrator", 8200),
        ("bff", "bff", "sunshine-bff", 8001),
        ("gateway", "gateway", "sunshine-gateway", 8000),
        ("auth", "auth-center", "sunshine-auth", 8100),
    ]
    for name, module, artifact, port in services:
        if wait_port(port, timeout=2):
            print(f"[SKIP] {name} :{port} already up")
            continue
        print(f"[START] {name} :{port}")
        start_java_detached(module, artifact, service_name=name, wait_sec=8)
    for name, _, _, port in services:
        if not wait_port(port):
            raise RuntimeError(f"service not ready: {name} :{port}")


def restart_hitl_services() -> None:
    from sunshine_lib import start_java_detached, stop_listening_port

    for port, name, module, artifact in [
        (8210, "tool-manager", "tool-manager", "sunshine-tool-manager"),
        (8200, "orchestrator", "orchestrator", "sunshine-orchestrator"),
        (8001, "bff", "bff", "sunshine-bff"),
    ]:
        if stop_listening_port(port):
            print(f"[RESTART] stopped :{port}")
            time.sleep(2)
        print(f"[RESTART] starting {name} :{port}")
        start_java_detached(module, artifact, service_name=name, wait_sec=10)
        if not wait_port(port):
            raise RuntimeError(f"restart failed: {name} :{port}")


def register_and_login(gw: str) -> str:
    user = f"hitl{uuid.uuid4().hex[:8]}"
    requests.post(
        f"{gw}/api/auth/register",
        json={"username": user, "password": "password123", "nickname": "hitl"},
        timeout=30,
    ).raise_for_status()
    token = requests.post(
        f"{gw}/api/auth/login",
        json={"username": user, "password": "password123"},
        timeout=30,
    ).json()["data"]["token"]
    return token


def assert_write_tool_in_catalog(gw: str, token: str) -> None:
    # 经 BFF 无 catalog 路由，直连 tool-manager
    resp = requests.get("http://127.0.0.1:8210/api/tools/catalog", timeout=30)
    resp.raise_for_status()
    entries = resp.json().get("data") or []
    hit = next((e for e in entries if e.get("id") == "approve_oa_task"), None)
    if not hit:
        raise AssertionError("catalog 缺少 approve_oa_task")
    if hit.get("sideEffect") != "write":
        raise AssertionError(f"approve_oa_task sideEffect={hit.get('sideEffect')!r}，期望 write")
    print(f"[OK] catalog approve_oa_task sideEffect=write displayName={hit.get('displayName')}")


class SseCollector:
    def __init__(self) -> None:
        self.confirmation: dict | None = None
        self.steps: list[dict] = []
        self.message_status: str | None = None
        self.content_chunks: list[str] = []
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE 未在超时内结束")

    def parse_line(self, line: str) -> None:
        if not line.startswith("data:"):
            return
        payload = line[5:].strip()
        if not payload:
            return
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            return
        t = obj.get("type")
        if t == "confirmation":
            self.confirmation = obj
        elif t == "step":
            self.steps.append(obj)
        elif t == "message" and obj.get("status"):
            self.message_status = obj["status"]
        elif t == "content" and obj.get("text"):
            self.content_chunks.append(obj["text"])


def chat_with_confirm(
    gw: str,
    token: str,
    conv_id: str,
    query: str,
    approved: bool | None,
) -> SseCollector:
    """approved=None 时不调用 confirm（用于检测是否弹出确认）。"""
    collector = SseCollector()
    confirm_called = threading.Event()

    def run_sse() -> None:
        try:
            headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
            body = {
                "content": query,
                "conversationId": conv_id,
                "executionPreference": "react",
            }
            with requests.post(
                f"{gw}/api/chat/stream",
                headers=headers,
                json=body,
                stream=True,
                timeout=(10, TIMEOUT_SEC),
            ) as resp:
                resp.raise_for_status()
                for raw in resp.iter_lines(decode_unicode=True):
                    if raw is None:
                        continue
                    line = raw.strip()
                    if line.startswith("data:"):
                        collector.parse_line(line)
                        if collector.confirmation and approved is not None and not confirm_called.is_set():
                            confirm_called.set()
                            token_val = collector.confirmation.get("confirmationToken")
                            r = requests.post(
                                f"{gw}/api/chat/confirm-tool",
                                headers=headers,
                                json={"token": token_val, "approved": approved},
                                timeout=30,
                            )
                            r.raise_for_status()
                            body = r.json()
                            if not body.get("accepted"):
                                raise AssertionError(f"confirm-tool rejected: {body}")
                            print(f"[OK] confirm-tool approved={approved} token={token_val[:8]}...")
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    t = threading.Thread(target=run_sse, daemon=True)
    t.start()
    collector.wait_done(TIMEOUT_SEC + 30)
    if collector.error:
        raise collector.error
    return collector


def create_conversation(gw: str, token: str) -> str:
    body = requests.post(
        f"{gw}/api/conversations",
        headers={"Authorization": f"Bearer {token}"},
        json={},
        timeout=30,
    ).json()
    conv_id = (body.get("data") or body).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {body}")
    return conv_id


def find_tool_steps(steps: list[dict]) -> list[dict]:
    return [s for s in steps if str(s.get("id", "")).startswith("tool-approve_oa_task")]


def main() -> int:
    parser = argparse.ArgumentParser(description="HITL write-tool live verification")
    parser.add_argument("--gateway", default=os.environ.get("GATEWAY_URL", "http://localhost:8000"))
    parser.add_argument("--restart", action="store_true", help="重启 tool-manager/orchestrator/bff")
    parser.add_argument("--start-missing", action="store_true", help="启动缺失的核心服务")
    args = parser.parse_args()
    gw = args.gateway.rstrip("/")

    if args.start_missing:
        start_services()
    if args.restart:
        restart_hitl_services()
        time.sleep(3)

    for port in (8000, 8001, 8200, 8210):
        if not wait_port(port, timeout=5):
            print(f"[FAIL] :{port} 未就绪，请先启动服务或加 --restart")
            return 1

    print(f"=== HITL Live ===\nGateway={gw}")
    token = register_and_login(gw)
    assert_write_tool_in_catalog(gw, token)

    query = "请调用 approve_oa_task 工具审批 OA 待办 taskId=T1001，不要查询其它工具。"

    # 场景 1：拒绝
    conv_deny = create_conversation(gw, token)
    print("\n[SCENARIO] deny write tool")
    deny = chat_with_confirm(gw, token, conv_deny, query, approved=False)
    if not deny.confirmation:
        print("[FAIL] 未收到 SSE type:confirmation")
        return 1
    print(f"[OK] confirmation toolId={deny.confirmation.get('toolId')} params={deny.confirmation.get('paramsSummary')}")
    tool_steps = find_tool_steps(deny.steps)
    skipped = [s for s in tool_steps if s.get("lifecycle") == "skipped" or s.get("status") == "skipped"]
    if not skipped:
        print(f"[FAIL] 拒绝后未见 skipped 工具步骤，steps={json.dumps(tool_steps, ensure_ascii=False)[:400]}")
        return 1
    print("[OK] deny → tool step skipped")

    # 场景 2：确认
    conv_ok = create_conversation(gw, token)
    print("\n[SCENARIO] approve write tool")
    ok = chat_with_confirm(gw, token, conv_ok, query, approved=True)
    if not ok.confirmation:
        print("[FAIL] 未收到 SSE type:confirmation (approve path)")
        return 1
    tool_steps = find_tool_steps(ok.steps)
    done = [s for s in tool_steps if s.get("lifecycle") == "done" or s.get("status") == "done"]
    if not done:
        print(f"[FAIL] 确认后工具步骤未完成，steps={json.dumps(tool_steps, ensure_ascii=False)[:400]}")
        return 1
    content = "".join(ok.content_chunks)
    if "T1001" not in content and "审批" not in content:
        print(f"[WARN] 答复未含预期关键词，content={content[:200]!r}")
    print("[OK] approve → tool step done")

    print("\n[PASS] HITL PR-1 Live")
    return 0


if __name__ == "__main__":
    sys.exit(main())
