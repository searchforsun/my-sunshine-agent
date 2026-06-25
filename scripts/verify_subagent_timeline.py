#!/usr/bin/env python3
"""验证 workflow agent 节点 subSteps 流式下发与持久化。"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    print("pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8000").rstrip("/")
TIMEOUT = int(os.environ.get("SUBAGENT_VERIFY_TIMEOUT_SEC", "180"))
QUERY = os.environ.get("SUBAGENT_VERIFY_QUERY", "待审批报销是否合规")


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def main() -> int:
    user = f"subagent_{datetime.now():%H%M%S}"
    password = "password123"
    auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "Verify"}, None)
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = login["data"]["token"]
    conv_resp = auth_json("POST", "/api/conversations", None, token)
    conv_id = conv_resp.get("data", {}).get("id") or conv_resp.get("id")
    print(f"conversation={conv_id}")

    payload = json.dumps({"content": QUERY, "conversationId": conv_id}, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name

    curl = shutil.which("curl")
    if not curl:
        print("FAIL: curl not found", file=sys.stderr)
        return 1

    proc = subprocess.run(
        [
            curl, "-N", "-s", "-m", str(TIMEOUT),
            "-X", "POST", f"{GATEWAY}/api/chat/stream",
            "-H", f"Authorization: Bearer {token}",
            "-H", "Content-Type: application/json",
            "--data-binary", f"@{tmp}",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    os.unlink(tmp)
    raw = proc.stdout or proc.stderr

    substeps_sse_batches = 0
    max_sub_count = 0
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        try:
            obj = json.loads(line[5:].strip())
        except json.JSONDecodeError:
            continue
        if obj.get("type") == "step" and obj.get("id") == "node-analyze":
            subs = obj.get("subSteps") or []
            if subs:
                substeps_sse_batches += 1
                max_sub_count = max(max_sub_count, len(subs))

    assistant = None
    for _ in range(60):
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            assistant = assistants[-1]
            break
        time.sleep(2)

    if not assistant:
        print("FAIL: assistant not completed within 120s")
        return 1

    steps_raw = assistant.get("steps") or []
    if isinstance(steps_raw, str):
        try:
            steps = json.loads(steps_raw)
        except json.JSONDecodeError:
            steps = []
    else:
        steps = steps_raw
    node_step = next((s for s in steps if s.get("id") == "node-analyze"), None)
    answer_step = next((s for s in steps if s.get("id") == "node-answer"), None)
    sub_steps = (node_step or {}).get("subSteps") or []
    sub_ids = [s.get("id") for s in sub_steps if s.get("id")]
    has_think = any(str(i).startswith("think") for i in sub_ids)
    has_tool = any(str(i).startswith("tool-") for i in sub_ids)

    print(f"SSE subSteps batches: {substeps_sse_batches} (max {max_sub_count} steps/batch)")
    print(f"Persisted subSteps: {len(sub_steps)}")
    print(f"subStep ids: {sub_ids}")
    print(f"has think: {has_think}, has tool: {has_tool}")
    print(f"content chars: {len(assistant.get('content') or '')}")

    print(f"answer step.result chars: {len((answer_step or {}).get('result') or '')}")
    if (answer_step or {}).get("result") == "未生成内容":
        print("WARN: answer step.result is placeholder")

    ok = len(sub_steps) >= 2 and has_think and has_tool
    answer_ok = answer_step and (answer_step.get("result") or "").strip() not in ("", "未生成内容")
    print(f"answer result OK: {answer_ok}")
    ok = ok and answer_ok
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
