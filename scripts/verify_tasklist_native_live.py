#!/usr/bin/env python3
"""原生 TaskList（todo_write）Live 验收 — AS2 P3。

验证点:
  N1  ≥3 子目标 query → SSE 出现 tasks 步且含 items（任务板渲染数据）
  N2  全程无 manage_tasks 工具调用（自研已下线）
  N3  无「任务板的工具结果综合分析」单独 think 步（todo_write 不触发 recordToolCompleted）

用法:
  python scripts/verify_tasklist_native_live.py
  GATEWAY_URL=http://127.0.0.1:8000 python scripts/verify_tasklist_native_live.py
环境变量: GATEWAY_URL, TASKLIST_TIMEOUT_SEC
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("TASKLIST_TIMEOUT_SEC", "180"))
QUERY = "先检索差旅报销制度，再查询待审批报销单，逐条做合规分析，最后给出整体风险结论"


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"tasklist_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "TaskList"}, None)
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


def chat_sse(token: str, conv_id: str, query: str, preference: str = "react") -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    payload = json.dumps(
        {"content": query, "conversationId": conv_id, "executionPreference": preference},
        ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [curl, "-N", "-s", "-m", str(TIMEOUT_SEC), "-X", "POST",
             f"{GATEWAY_URL}/api/chat/stream",
             "-H", f"Authorization: Bearer {token}",
             "-H", "Content-Type: application/json",
             "--data-binary", f"@{tmp}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE failed curl exit={proc.returncode}")
        return raw
    finally:
        os.unlink(tmp)


def parse_sse_steps(raw: str) -> list[dict]:
    steps: list[dict] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            obj = json.loads(payload)
        except Exception:
            continue
        if obj.get("type") == "step":
            steps.append(obj)
    return steps


def main() -> int:
    print("=== 原生 TaskList（todo_write）Live 验收 ===")
    print(f"Gateway={GATEWAY_URL}")
    token, conv_id = setup_auth()
    print(f"conversation={conv_id}\nquery={QUERY}\n")
    raw = chat_sse(token, conv_id, QUERY)
    steps = parse_sse_steps(raw)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"steps={step_ids}")

    fails: list[str] = []

    # N1: tasks 步含 items
    tasks = [s for s in steps if str(s.get("id")) == "tasks"]
    item_count = 0
    if tasks:
        meta = tasks[-1].get("metadata") or {}
        items = meta.get("tasks") or []
        item_count = len(items) if isinstance(items, list) else 0
    if item_count >= 1:
        print(f"[OK] N1 tasks 步含 {item_count} 个 items")
    else:
        fails.append(f"N1 tasks 步无 items（tasks 步数={len(tasks)}）")

    # N2: 无 manage_tasks
    if "manage_tasks" in raw:
        fails.append("N2 SSE 残留 manage_tasks（自研未下线）")
    else:
        print("[OK] N2 全程无 manage_tasks")

    # N3: 无任务板单独综合分析 think 步
    think_board = [s for s in steps
                   if "任务板的工具结果综合分析" in json.dumps(s, ensure_ascii=False)]
    if think_board:
        fails.append(f"N3 出现任务板单独综合分析 think 步 x{len(think_board)}")
    else:
        print("[OK] N3 无「任务板的工具结果综合分析」think 步")

    if fails:
        print("\n[FAIL]")
        for f in fails:
            print(f"  - {f}")
        return 1
    print("\n[PASS] 原生 TaskList Live 验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
