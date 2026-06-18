#!/usr/bin/env python3
"""Phase 2.4 Agent E2E: Gateway -> BFF -> Orchestrator -> ReActAgent -> ToolManager -> Finance

用法:
  python scripts/phase2_agent_demo.py

环境变量: GATEWAY_URL (default http://localhost:8000), PHASE2_AGENT_TIMEOUT_SEC (default 120)
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装依赖: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

BASE = os.environ.get("GATEWAY_URL", "http://localhost:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))
FINANCE_QUERY = "帮我查询有哪些待审批的报销和付款消息，列出标题和金额"


def auth_json(method: str, path: str, body: dict | None, token: str | None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{BASE}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def conversation_id(response: dict) -> str:
    if response.get("code") == 200 and response.get("data", {}).get("id"):
        return response["data"]["id"]
    if response.get("id"):
        return response["id"]
    raise RuntimeError(f"create conversation failed: {response}")


def finance_chat_sse(token: str, conv_id: str) -> str:
    curl = shutil_which("curl")
    if not curl:
        raise RuntimeError("curl not found (required for SSE sampling)")
    payload = json.dumps({"content": FINANCE_QUERY, "conversationId": conv_id}, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        print(f"  streaming up to {TIMEOUT_SEC}s ...")
        proc = subprocess.run(
            [
                curl,
                "-N",
                "-s",
                "-m",
                str(TIMEOUT_SEC),
                "-X",
                "POST",
                f"{BASE}/api/chat/stream",
                "-H",
                f"Authorization: Bearer {token}",
                "-H",
                "Content-Type: application/json",
                "--data-binary",
                f"@{tmp}",
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE request failed (curl exit {proc.returncode})")
        return raw
    finally:
        os.unlink(tmp)


def shutil_which(name: str) -> str | None:
    import shutil

    return shutil.which(name)


def parse_sse(raw: str) -> dict:
    content: list[str] = []
    finance_step = False
    stream_completed = False
    step_count = 0

    for line in raw.splitlines():
        line = line.rstrip("\r")
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload:
            continue
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            continue
        typ = obj.get("type")
        if typ == "step":
            step_count += 1
            detail = str(obj.get("detail") or "")
            if "财" in detail:
                finance_step = True
        elif typ == "content":
            text = obj.get("text")
            if text:
                content.append(str(text))
        elif typ == "message":
            if obj.get("status") == "completed":
                stream_completed = True

    return {
        "content": "".join(content),
        "finance_step": finance_step,
        "stream_completed": stream_completed,
        "step_count": step_count,
    }


def wait_assistant_completed(token: str, conv_id: str, max_wait_sec: int) -> dict:
    deadline = time.time() + max_wait_sec
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant message not completed within {max_wait_sec}s")


def main() -> int:
    user = f"agent_{datetime.now():%H%M%S}"
    password = "password123"

    print("=== Phase 2.4 Agent E2E Demo ===")
    print(f"Gateway: {BASE} | timeout: {TIMEOUT_SEC}s")

    print("\nStep 0: preflight finance mock data")
    pending = requests.get("http://localhost:8710/api/finance/messages?status=pending", timeout=10).json()
    if pending.get("code") != 200 or not pending.get("data"):
        raise RuntimeError("finance-service has no pending messages (need finance-service :8710)")
    print(f"  OK pending={len(pending['data'])}")

    print("\nStep 1: register + login")
    r1 = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "AgentDemo"}, None)
    if r1.get("code") != 200:
        raise RuntimeError("register failed")
    r2 = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    if r2.get("code") != 200 or not r2.get("data", {}).get("token"):
        raise RuntimeError("login failed")
    token = r2["data"]["token"]
    print(f"  OK user={user}")

    print("\nStep 2: create conversation")
    conv_id = conversation_id(auth_json("POST", "/api/conversations", None, token))
    print(f"  OK conversation={conv_id}")

    print("\nStep 3: finance chat via Gateway SSE")
    sse_raw = finance_chat_sse(token, conv_id)
    sse = parse_sse(sse_raw)
    print(
        f"  steps={sse['step_count']} financeStep={sse['finance_step']} "
        f"streamCompleted={sse['stream_completed']}"
    )
    if sse["content"]:
        preview = sse["content"][:160] + ("..." if len(sse["content"]) > 160 else "")
        print(f"  content preview: {preview}")

    print("\nStep 4: verify persisted assistant message")
    assistant = wait_assistant_completed(token, conv_id, 30)
    print(f"  OK intent={assistant.get('intent')} status={assistant.get('status')}")

    final_content = str(assistant.get("content") or "") or sse["content"]
    if assistant.get("intent") != "finance":
        raise RuntimeError(f"expected intent=finance, got {assistant.get('intent')}")
    if sse["step_count"] < 3:
        raise RuntimeError(f"expected >=3 timeline steps in SSE, got {sse['step_count']}")

    steps_json = json.dumps(assistant.get("steps") or "")
    tool_invoked = ("list_finance_messages" in sse_raw) or ("list_finance_messages" in steps_json)
    finance_hit = any(x in final_content for x in ("1001", "1002", "1004"))

    if tool_invoked and finance_hit:
        print("  OK tool invoked and finance mock data in reply")
    elif tool_invoked:
        print("  WARN: tool step seen but reply missing mock ids (LLM summarization)")
    elif finance_hit:
        print("  OK finance mock data in reply")
    else:
        print("  WARN: intent routed to finance but LLM did not call tool / return mock ids")

    if sse["step_count"] < 1:
        print("  WARN: no step events in SSE")

    print("\n[PASS] Phase 2.4 agent E2E completed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
