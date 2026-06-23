#!/usr/bin/env python3
"""Timeline QueryRewrite 验收 — 解析 SSE step 的 detail/metadata。

用法:
  python scripts/verify_rewrite_timeline.py
  python scripts/verify_rewrite_timeline.py --query 报差旅 --expect-step node-rag
  python scripts/verify_rewrite_timeline.py --query 待审批 --expect-step intent

环境变量: GATEWAY_URL（默认 http://ecs4c16g:8000）
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile

from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))


def auth_json(method: str, path: str, body: dict | None, token: str | None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"rewrite_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "RewriteVerify"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login_resp = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    if login_resp.get("code") != 200 or not login_resp.get("data", {}).get("token"):
        raise RuntimeError(f"login failed: {login_resp}")
    token = login_resp["data"]["token"]
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, conv_id


def chat_sse(token: str, conv_id: str, query: str) -> str:
    import shutil
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
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
        return proc.stdout or proc.stderr
    finally:
        os.unlink(tmp)


def parse_steps(raw: str) -> list[dict]:
    steps: list[dict] = []
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload:
            continue
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if obj.get("type") == "step" and obj.get("lifecycle") == "done":
            steps.append(obj)
    return steps


def find_step(steps: list[dict], step_id: str) -> dict | None:
    for s in steps:
        if str(s.get("id")) == step_id:
            return s
    return None


def rewrite_applied(step: dict | None) -> bool:
    if not step:
        return False
    meta = step.get("metadata") or {}
    if meta.get("rewriteApplied") is True:
        return True
    detail = str(step.get("detail") or "")
    return "原问题：" in detail


def main() -> int:
    parser = argparse.ArgumentParser(description="Timeline QueryRewrite 验收")
    parser.add_argument("--query", default="报差旅", help="用户问题")
    parser.add_argument("--expect-step", default="node-rag", help="期望含改写的步骤 id")
    parser.add_argument("--require-rewrite", action="store_true", default=True,
                        help="要求 rewriteApplied 或 detail 含改写前（默认开启）")
    parser.add_argument("--no-require-rewrite", dest="require_rewrite", action="store_false")
    args = parser.parse_args()

    print(f"[verify] GATEWAY={GATEWAY_URL} query={args.query!r} step={args.expect_step}")
    token, conv_id = setup_auth()
    raw = chat_sse(token, conv_id, args.query)
    steps = parse_steps(raw)
    step = find_step(steps, args.expect_step)

    print(f"[verify] done steps: {[s.get('id') for s in steps]}")
    if not step:
        print(f"FAIL: 未找到步骤 {args.expect_step}", file=sys.stderr)
        return 1

    detail = step.get("detail")
    meta = step.get("metadata")
    after = (step.get("summary") or {}).get("after")
    print(f"[verify] after: {after}")
    print(f"[verify] detail: {detail}")
    print(f"[verify] metadata: {json.dumps(meta, ensure_ascii=False) if meta else None}")

    if args.require_rewrite and not rewrite_applied(step):
        print("WARN: 本轮改写未 applied（LLM 可能返回与原文相同 query）", file=sys.stderr)
        print("      可换 query：报差旅 / 待审批；或查 orchestrator 日志 [QueryRewrite]", file=sys.stderr)
        return 2

    if rewrite_applied(step):
        print("PASS: Timeline 含改写 detail/metadata")
    else:
        print("PASS: 步骤完成（改写未触发，属可接受）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
