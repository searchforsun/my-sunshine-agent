#!/usr/bin/env python3
"""个人规则（Personal Rules / soul）注入 Live 验收。

场景：
  P1 设置规则 → me() 返回该规则
  P2 ReAct 生效（请求体带 personalRules → 回答体现规则）
  P3 Workflow 生效（强制 workflow → answer 体现规则）
  P4 清空规则 → me() 为 null → 回答不再体现
  P5 服务端有规则但请求体不带 → 不注入（证明来源是请求透传而非服务端状态）

用法:
  python3 scripts/verify_personal_rules_live.py
  GATEWAY_URL=http://localhost:8000 python3 scripts/verify_personal_rules_live.py

环境变量: GATEWAY_URL, PHASE2_AGENT_TIMEOUT_SEC
"""
from __future__ import annotations

import json
import os
import shutil
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

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://localhost:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))
RULE = "无论用户问什么，回答正文开头必须先写「领命」二字，然后再作答。"
MARKER = "领命"


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> requests.Response:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)


def setup_auth() -> str:
    user = f"rules_{datetime.now():%H%M%S}"
    password = "password123"
    r1 = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "RulesDemo"}, None)
    if r1.status_code != 200 or r1.json().get("code") != 200:
        raise RuntimeError(f"register failed: {r1.text}")
    r2 = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (r2.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError("login failed")
    return token


def patch_rules(token: str, rules: str | None) -> dict:
    body = {"nickname": "RulesDemo", "tenantId": "default", "personalRules": rules}
    resp = auth_json("PATCH", "/api/auth/profile", body, token)
    if resp.status_code != 200 or resp.json().get("code") != 200:
        raise RuntimeError(f"patch profile failed: {resp.text}")
    return resp.json().get("data") or {}


def me_rules(token: str):
    resp = auth_json("GET", "/api/auth/me", None, token)
    return (resp.json().get("data") or {}).get("personalRules")


def chat_once(token: str, query: str, *, preference: str | None = None,
              workflow_id: str | None = None, personal_rules: str | None = None) -> dict:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.json().get("data") or conv.json()).get("id")
    body: dict = {"content": query, "conversationId": conv_id}
    if preference:
        body["executionMode"] = preference
    if workflow_id:
        body["workflowId"] = workflow_id
    if personal_rules:
        body["personalRules"] = personal_rules
    payload = json.dumps(body, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        curl = shutil.which("curl")
        subprocess.run(
            [curl, "-N", "-s", "-m", str(TIMEOUT_SEC),
             "-X", "POST", f"{GATEWAY_URL}/api/chat/stream",
             "-H", f"Authorization: Bearer {token}",
             "-H", "Content-Type: application/json",
             "--data-binary", f"@{tmp}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
    finally:
        os.unlink(tmp)
    deadline = time.time() + TIMEOUT_SEC
    while time.time() < deadline:
        resp = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        if resp.status_code == 200:
            messages = resp.json().get("messages") or resp.json().get("data", {}).get("messages") or []
            assistants = [m for m in messages if m.get("role") == "assistant"]
            if assistants and assistants[-1].get("status") in ("completed", "interrupted", "failed"):
                return assistants[-1]
        time.sleep(2)
    raise RuntimeError("assistant not completed in time")


def content_of(assistant: dict) -> str:
    content = assistant.get("content")
    if isinstance(content, str) and content.strip():
        return content
    if isinstance(content, list):
        merged = "".join(str(b.get("text") or b.get("content") or "") for b in content if isinstance(b, dict))
        if merged.strip():
            return merged
    # workflow 终态 answer 仅经 step_delta(result) 下发（SSE SSOT），message.content 可能为空
    parts: list[str] = []
    steps = assistant.get("steps")
    if isinstance(steps, str) and steps.strip():
        try:
            steps = json.loads(steps)
        except json.JSONDecodeError:
            steps = []
    for step in steps or []:
        if step.get("id") == "node-answer" and isinstance(step.get("result"), str):
            parts.append(step["result"])
    return "\n".join(parts)


def main() -> int:
    print(f"=== Personal Rules Live 验收 ===\nGateway={GATEWAY_URL}")
    token = setup_auth()
    report: dict = {"steps": {}}

    # P1 设置规则
    data = patch_rules(token, RULE)
    token = data.get("token") or token
    got = me_rules(token)
    report["steps"]["P1"] = {"pass": got == RULE, "personalRules": got}
    print(f"[P1] 设置规则 → me() = {str(got)[:30]}... pass={got == RULE}")

    # P2 ReAct 生效
    assistant = chat_once(token, "你好", preference="fast", personal_rules=RULE)
    text = content_of(assistant)
    ok = MARKER in text
    report["steps"]["P2"] = {"pass": ok, "status": assistant.get("status"), "head": text[:40]}
    print(f"[P2] react 回答开头 = {text[:20]!r} pass={ok}")

    # P3 Workflow 生效（强制 workflow 走 finance-list 标杆，answer 节点注入）
    assistant = chat_once(token, "有哪些待审批报销", preference="workflow", personal_rules=RULE)
    text = content_of(assistant)
    ok = MARKER in text
    report["steps"]["P3"] = {"pass": ok, "status": assistant.get("status"), "head": text[:40]}
    print(f"[P3] workflow 回答开头 = {text[:20]!r} pass={ok}")

    # P4 清空规则 → 不再注入
    data = patch_rules(token, "")
    token = data.get("token") or token
    cleared = me_rules(token)
    assistant = chat_once(token, "你好", preference="fast")
    text = content_of(assistant)
    ok = cleared is None and MARKER not in text
    report["steps"]["P4"] = {"pass": ok, "cleared": cleared, "head": text[:40]}
    print(f"[P4] 清空后 me()={cleared} 回答开头 = {text[:20]!r} pass={ok}")

    # P5 服务端有规则但请求体不带 → 不注入
    data = patch_rules(token, RULE)
    token = data.get("token") or token
    assistant = chat_once(token, "你好", preference="fast")
    text = content_of(assistant)
    ok = MARKER not in text
    report["steps"]["P5"] = {"pass": ok, "head": text[:40]}
    print(f"[P5] 请求体不带规则 回答开头 = {text[:20]!r} pass={ok}")

    # 收尾：清空规则，避免演示数据残留
    patch_rules(token, "")

    failed = [k for k, v in report["steps"].items() if not v.get("pass")]
    print("\n=== Report ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failed:
        print(f"\n[FAIL] cases: {failed}", file=sys.stderr)
        return 1
    print("\n[PASS] personal rules live")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
