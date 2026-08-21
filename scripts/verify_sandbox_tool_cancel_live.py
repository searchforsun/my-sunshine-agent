#!/usr/bin/env python3
"""沙箱工具单次取消 Live — 诱导长 exec → cancel by stepId → 主消息 completed。

用法:
  python3 scripts/verify_sandbox_tool_cancel_live.py

前置: sync_nacos + restart orchestrator / sandbox-service / bff
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
from datetime import datetime
from typing import Any
from urllib.parse import quote

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("SANDBOX_TOOL_CANCEL_TIMEOUT_SEC", "180"))

QUERY = (
    "请调用 sandbox__exec：command 必须是 sleep 120，并传 timeout_sec=180；"
    "不要自己提前结束。若工具返回「用户已取消」，请换方案执行 echo cancel-ok 并作答。"
)


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup() -> tuple[str, str]:
    user = f"stc_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST", "/api/auth/register",
        {"username": user, "password": password, "nickname": "SandboxToolCancel"}, None,
    )
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, conv_id


class Collector:
    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.generation_id: str | None = None
        self.message_status: str | None = None
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_until(self, pred, timeout: float) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if pred(self):
                return
            if self._done.is_set() and self.error:
                raise self.error
            time.sleep(0.2)
        raise TimeoutError("wait_until timeout")

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE timeout")


def is_cancellable_tool_step(step: dict) -> bool:
    sid = str(step.get("id") or "")
    if not sid.startswith("tool-"):
        return False
    tool = sid[len("tool-"):].split("@")[0]
    return tool in ("sandbox__exec", "sandbox__grep", "sandbox__glob")


def latest_running_cancellable(steps: list[dict]) -> dict | None:
    for s in reversed(steps):
        if is_cancellable_tool_step(s) and str(s.get("lifecycle") or "") == "running":
            return s
    return None


def main() -> int:
    print(f"=== sandbox tool cancel Live ===\nGateway={GATEWAY_URL}")
    token, conv_id = setup()
    coll = Collector()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    def run() -> None:
        try:
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers=headers,
                json={
                    "content": QUERY,
                    "conversationId": conv_id,
                    "executionMode": "fast",
                    "writeHitlMode": "always",
                },
                stream=True,
                timeout=(10, TIMEOUT_SEC),
            ) as resp:
                resp.raise_for_status()
                for raw in resp.iter_lines(decode_unicode=True):
                    if raw is None or not raw.startswith("data:"):
                        continue
                    payload = raw[5:].strip()
                    if not payload:
                        continue
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    t = obj.get("type")
                    if t == "generation" and obj.get("id"):
                        coll.generation_id = str(obj["id"])
                    elif t == "step":
                        coll.steps.append(obj)
                    elif t == "message" and obj.get("status"):
                        coll.message_status = str(obj["status"])
        except Exception as e:
            coll.error = e
        finally:
            coll._done.set()

    threading.Thread(target=run, daemon=True).start()
    coll.wait_until(
        lambda c: bool(c.generation_id) and latest_running_cancellable(c.steps) is not None,
        timeout=min(TIMEOUT_SEC, 90),
    )
    step = latest_running_cancellable(coll.steps)
    assert step and coll.generation_id
    step_id = str(step["id"])
    print(f"  generationId={coll.generation_id} stepId={step_id}")

    cancel = auth_json(
        "POST",
        f"/api/generations/{coll.generation_id}/tools/{quote(step_id, safe='')}/cancel",
        None,
        token,
    )
    status = (cancel.get("data") or cancel).get("status") or cancel.get("status")
    print(f"  cancel_api={status} raw={cancel}")

    coll.wait_done(TIMEOUT_SEC + 30)
    deadline = time.time() + min(TIMEOUT_SEC, 120)
    assistant: dict[str, Any] = {}
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            assistant = assistants[-1]
            break
        time.sleep(2)
    if not assistant:
        raise RuntimeError("assistant not completed")

    paused = [
        s for s in coll.steps
        if is_cancellable_tool_step(s) and str(s.get("lifecycle") or "") == "paused"
    ]
    msg_status = str(assistant.get("status") or "")
    hard_ok = msg_status == "completed" and status == "CANCELLED" and len(paused) >= 1
    print(f"  msg={msg_status} paused_steps={len(paused)} preview={(assistant.get('content') or '')[:160]}")
    report = {
        "pass": hard_ok,
        "cancel_status": status,
        "message_status": msg_status,
        "paused_count": len(paused),
        "content_preview": (assistant.get("content") or "")[:200],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not hard_ok:
        raise RuntimeError("sandbox tool cancel live failed")
    print("\n[PASS] sandbox tool cancel Live")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
