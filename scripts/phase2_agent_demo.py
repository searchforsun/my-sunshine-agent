#!/usr/bin/env python3
"""Phase 2 Live 验收 — react / workflow / all 套件。

用法:
  python scripts/phase2_agent_demo.py --suite all
  python scripts/phase2_agent_demo.py --suite react
  python scripts/phase2_agent_demo.py --suite workflow
  python scripts/phase2_agent_demo.py --suite all --skip-rag-prep

环境变量: GATEWAY_URL, FINANCE_URL, RAG_URL, PHASE2_AGENT_TIMEOUT_SEC
"""
from __future__ import annotations

import argparse
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

from sunshine_lib import unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
FINANCE_URL = os.environ.get("FINANCE_URL", "http://ecs4c16g:8710").rstrip("/")
RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))
FINANCE_QUERY = "帮我查询有哪些待审批的报销和付款消息，列出标题和金额"


def auth_json(method: str, path: str, body: dict | None, token: str | None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def conversation_id(response: dict) -> str:
    if response.get("code") == 200 and response.get("data", {}).get("id"):
        return response["data"]["id"]
    if response.get("id"):
        return response["id"]
    raise RuntimeError(f"create conversation failed: {response}")


def shutil_which(name: str) -> str | None:
    import shutil
    return shutil.which(name)


def chat_sse(token: str, conv_id: str, query: str) -> str:
    curl = shutil_which("curl")
    if not curl:
        raise RuntimeError("curl not found (required for SSE sampling)")
    payload = json.dumps({"content": query, "conversationId": conv_id}, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [
                curl, "-N", "-s", "-m", str(TIMEOUT_SEC),
                "-X", "POST", f"{GATEWAY_URL}/api/chat/stream",
                "-H", f"Authorization: Bearer {token}",
                "-H", "Content-Type: application/json",
                "--data-binary", f"@{tmp}",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE request failed (curl exit {proc.returncode})")
        return raw
    finally:
        os.unlink(tmp)


def parse_sse(raw: str) -> dict:
    content: list[str] = []
    stream_completed = False
    step_count = 0
    step_ids: list[str] = []

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
            sid = str(obj.get("id") or obj.get("stepId") or "")
            if sid:
                step_ids.append(sid)
        elif typ == "content":
            text = obj.get("text")
            if text:
                content.append(str(text))
        elif typ == "message":
            if obj.get("status") == "completed":
                stream_completed = True

    return {
        "content": "".join(content),
        "stream_completed": stream_completed,
        "step_count": step_count,
        "step_ids": step_ids,
        "raw": raw,
    }


def wait_assistant_completed(token: str, conv_id: str, max_wait_sec: int = 30) -> dict:
    deadline = time.time() + max_wait_sec
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant message not completed within {max_wait_sec}s")


def preflight_finance() -> None:
    pending = requests.get(f"{FINANCE_URL}/api/finance/messages?status=pending", timeout=10).json()
    if pending.get("code") != 200 or not pending.get("data"):
        raise RuntimeError("finance-service has no pending messages")
    print(f"  OK finance pending={len(pending['data'])}")


def preflight_rag() -> None:
    resp = requests.post(
        f"{RAG_URL}/api/rag/search",
        json={"query": "年假可以请几天", "topK": 3},
        timeout=30,
    )
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="rag preflight") or {}
    results = data.get("results") or []
    if not results:
        raise RuntimeError("rag search returned empty for leave query")
    print(f"  OK rag search hits={len(results)}")


def setup_auth() -> tuple[str, str]:
    user = f"agent_{datetime.now():%H%M%S}"
    password = "password123"
    r1 = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "AgentDemo"}, None)
    if r1.get("code") != 200:
        raise RuntimeError("register failed")
    r2 = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    if r2.get("code") != 200 or not r2.get("data", {}).get("token"):
        raise RuntimeError("login failed")
    token = r2["data"]["token"]
    conv_id = conversation_id(auth_json("POST", "/api/conversations", None, token))
    print(f"  OK user={user} conversation={conv_id}")
    return token, conv_id


def run_react_finance(token: str, conv_id: str) -> dict:
    print("\n[react-finance] SSE chat")
    sse_raw = chat_sse(token, conv_id, FINANCE_QUERY)
    sse = parse_sse(sse_raw)
    assistant = wait_assistant_completed(token, conv_id, 30)
    steps_json = json.dumps(assistant.get("steps") or "")
    tool_invoked = ("list_finance_messages" in sse_raw) or ("list_finance_messages" in steps_json)
    content = str(assistant.get("content") or "") or sse["content"]
    finance_hit = any(x in content for x in ("1001", "1002", "1004", "待审批", "报销"))
    ok = sse["step_count"] >= 2 and (tool_invoked or finance_hit)
    return {"pass": ok, "step_count": sse["step_count"], "tool_invoked": tool_invoked, "finance_hit": finance_hit}


def run_workflow_chat(token: str, conv_id: str, query: str, label: str, *, expect_tool: str | None = None, expect_agent: bool = False, expect_finance_data: bool = False) -> dict:
    print(f"\n[{label}] query={query}")
    sse_raw = chat_sse(token, conv_id, query)
    sse = parse_sse(sse_raw)
    assistant = wait_assistant_completed(token, conv_id, 60)
    content = str(assistant.get("content") or "") or sse["content"]
    steps_json = json.dumps(assistant.get("steps") or "")
    ok = sse["step_count"] >= 2 and bool(content.strip())
    if expect_tool:
        tool_hit = (
            expect_tool in sse_raw
            or expect_tool in steps_json
            or f"tool-{expect_tool}" in sse_raw
            or "node-finance-list" in sse_raw
            or "node-finance-list" in steps_json
        )
        ok = ok and tool_hit
    if expect_agent:
        ok = ok and ("agent" in steps_json or "node-" in steps_json)
    if expect_finance_data:
        ok = ok and any(x in content for x in ("1001", "1002", "1004", "待审批", "报销"))
    return {"pass": ok, "step_count": sse["step_count"], "content_len": len(content)}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--suite", choices=["all", "react", "workflow"], default="all")
    p.add_argument("--skip-rag-prep", action="store_true")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    print(f"=== Phase 2 Live Demo suite={args.suite} ===")
    print(f"Gateway={GATEWAY_URL} Finance={FINANCE_URL} RAG={RAG_URL}")

    print("\nStep 0: preflight")
    preflight_finance()
    if not args.skip_rag_prep and args.suite in ("all", "workflow"):
        preflight_rag()

    print("\nStep 1: auth")
    token, conv_id = setup_auth()

    report: dict = {"suite": args.suite, "steps": {}}

    if args.suite in ("all", "react"):
        report["steps"]["react-finance"] = run_react_finance(token, conv_id)

    if args.suite in ("all", "workflow"):
        conv2 = conversation_id(auth_json("POST", "/api/conversations", None, token))
        report["steps"]["wf-knowledge"] = run_workflow_chat(token, conv2, "年假可以请几天", "wf-knowledge")
        conv3 = conversation_id(auth_json("POST", "/api/conversations", None, token))
        report["steps"]["wf-finance-list"] = run_workflow_chat(
            token, conv3, "有哪些待审批报销", "wf-finance-list",
            expect_tool="list_finance_messages", expect_finance_data=True)
        conv4 = conversation_id(auth_json("POST", "/api/conversations", None, token))
        report["steps"]["wf-finance-smart"] = run_workflow_chat(
            token, conv4, "待审批报销是否合规", "wf-finance-smart", expect_agent=True)

    failed = [k for k, v in report["steps"].items() if not v.get("pass")]
    print("\n=== Report ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failed:
        raise RuntimeError(f"failed steps: {failed}")
    print(f"\n[PASS] phase2 suite={args.suite}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
