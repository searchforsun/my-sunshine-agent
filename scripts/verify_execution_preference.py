#!/usr/bin/env python3
"""Chat executionPreference 强制路由 Live 验收 — routing-golden-set §J。

用法:
  python scripts/verify_execution_preference.py
  GATEWAY_URL=http://localhost:8000 python scripts/verify_execution_preference.py

环境变量: GATEWAY_URL, PHASE2_AGENT_TIMEOUT_SEC
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

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://localhost:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))


def auth_json(method: str, path: str, body: dict | None, token: str | None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    return resp


def setup_auth() -> tuple[str, str]:
    user = f"pref_{datetime.now():%H%M%S}"
    password = "password123"
    r1 = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "PrefDemo"}, None)
    if r1.status_code != 200 or r1.json().get("code") != 200:
        raise RuntimeError(f"register failed: {r1.text}")
    r2 = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    data = r2.json().get("data") or {}
    token = data.get("token")
    if not token:
        raise RuntimeError("login failed")
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_body = conv.json()
    conv_id = conv_body.get("data", {}).get("id") or conv_body.get("id")
    if not conv_id:
        raise RuntimeError("create conversation failed")
    return token, conv_id


def effective_http_code(http_code: int, body: str) -> int:
    """Gateway/BFF 可能 HTTP 200 但 JSON 包装体 code=4xx。"""
    if http_code >= 400:
        return http_code
    text = body.strip()
    if not text:
        return http_code
    try:
        first = text.split("\n", 1)[0].strip()
        if first.startswith("{"):
            payload = json.loads(first)
            wrapped = payload.get("code")
            if isinstance(wrapped, int) and wrapped >= 400:
                return wrapped
            msg = str(payload.get("msg") or payload.get("message") or "")
            if '"status":400' in msg or "Bad Request" in msg:
                return 400
    except json.JSONDecodeError:
        pass
    if '"status":400' in text or "Bad Request" in text:
        return 400
    return http_code


def chat_sse_raw(token: str, conv_id: str, query: str, *, preference: str | None = None) -> tuple[int, str]:
    import shutil
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body: dict = {"content": query, "conversationId": conv_id}
    if preference and preference != "auto":
        body["executionPreference"] = preference
    payload = json.dumps(body, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [
                curl, "-N", "-s", "-w", "\n__HTTP_CODE__%{http_code}",
                "-m", str(TIMEOUT_SEC),
                "-X", "POST", f"{GATEWAY_URL}/api/chat/stream",
                "-H", f"Authorization: Bearer {token}",
                "-H", "Content-Type: application/json",
                "--data-binary", f"@{tmp}",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        raw = (proc.stdout or "") + (proc.stderr or "")
        code = 200
        if "__HTTP_CODE__" in raw:
            body_text, _, tail = raw.rpartition("__HTTP_CODE__")
            raw = body_text
            try:
                code = int(tail.strip())
            except ValueError:
                code = proc.returncode or 500
        code = effective_http_code(code, raw)
        return code, raw
    finally:
        os.unlink(tmp)


def wait_assistant(token: str, conv_id: str, max_wait: int = 90) -> dict:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        resp = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        if resp.status_code != 200:
            time.sleep(2)
            continue
        detail = resp.json()
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within {max_wait}s")


def parse_steps(raw_steps) -> list:
    if isinstance(raw_steps, list):
        return raw_steps
    if isinstance(raw_steps, str) and raw_steps.strip():
        try:
            return json.loads(raw_steps)
        except json.JSONDecodeError:
            return []
    return []


def intent_metadata(assistant: dict) -> dict:
    for step in parse_steps(assistant.get("steps")):
        if step.get("id") == "intent":
            return step.get("metadata") or {}
    return {}


def conv_preference(token: str, conv_id: str) -> str | None:
    resp = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
    if resp.status_code != 200:
        return None
    detail = resp.json()
    data = detail.get("data") or detail
    return data.get("executionPreference")


def run_case(token: str, case_id: str, preference: str, query: str, *, expect_reason: str | None = None,
             expect_skill: str | None = None, expect_http_error: bool = False) -> dict:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.json().get("data") or conv.json()).get("id")
    print(f"\n[{case_id}] pref={preference} query={query[:40]}...")
    http_code, sse_raw = chat_sse_raw(token, conv_id, query, preference=preference)
    if expect_http_error:
        ok = http_code >= 400 or "不支持 @Skill" in sse_raw
        return {"pass": ok, "http_code": http_code, "case": case_id}
    if http_code >= 400:
        return {"pass": False, "http_code": http_code, "case": case_id, "error": "unexpected http error"}
    assistant = wait_assistant(token, conv_id, 120)
    meta = intent_metadata(assistant)
    reason = meta.get("routingReason")
    skill = meta.get("skillId")
    stored_pref = conv_preference(token, conv_id)
    ok = True
    if expect_reason and reason != expect_reason:
        ok = False
    if expect_skill and skill != expect_skill:
        ok = False
    if stored_pref != preference:
        ok = False
    return {
        "pass": ok,
        "case": case_id,
        "routingReason": reason,
        "skillId": skill,
        "executionPreference": stored_pref,
    }


def main() -> int:
    print(f"=== executionPreference Live §J ===\nGateway={GATEWAY_URL}")
    token, _ = setup_auth()
    cases = [
        ("J1", "simple-llm", "写一段快速排序", {"expect_reason": "user:forced-simple-llm"}),
        ("J2", "react", "待审批是否合规", {"expect_reason": "user:forced-react"}),
        ("J3", "workflow", "年假可以请几天", {"expect_reason": "user:forced-workflow"}),
        ("J4", "plan-workflow", "先查制度再查待审批", {"expect_reason": "user:forced-plan-workflow"}),
        ("J5", "workflow", "@policy-review 审查", {"expect_http_error": True}),
        ("J6", "plan-workflow", "@finance-analysis 是否合规",
         {"expect_reason": "user:forced-plan-workflow", "expect_skill": "finance-analysis"}),
    ]
    report = {"steps": {}}
    for case_id, pref, query, kwargs in cases:
        report["steps"][case_id] = run_case(token, case_id, pref, query, **kwargs)
    failed = [k for k, v in report["steps"].items() if not v.get("pass")]
    print("\n=== Report ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failed:
        print(f"\n[FAIL] cases: {failed}", file=sys.stderr)
        return 1
    print("\n[PASS] executionPreference §J")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
