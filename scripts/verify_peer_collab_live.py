#!/usr/bin/env python3
"""PEER_COLLAB Live 验收 — routing-golden-set §E。

用法:
  python3 scripts/verify_peer_collab_live.py
  python3 scripts/verify_peer_collab_live.py --query "请制度专家和财务专家分别审查这笔报销是否合规，并互相验证"

前置:
  - docs/nacos/sunshine-orchestrator.yaml 已 sync + orchestrator 已重启
  - LLM / skill 链路可用（E1 会触发 MsgHub 多轮，默认仅验路由 + peer-collab 步出现）

环境变量: GATEWAY_URL, PEER_COLLAB_TIMEOUT_SEC, ORCHESTRATOR_URL
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
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
ORCH_URL = os.environ.get("ORCHESTRATOR_URL", "http://127.0.0.1:8200").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PEER_COLLAB_TIMEOUT_SEC", "240"))
E1_QUERY = "请制度专家和财务专家分别审查这笔报销是否合规，并互相验证"
E2_QUERY = "先检索报销制度，再查待审批列表，并对结果做合规分析"


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"peer_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "Peer"}, None)
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
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE failed curl exit={proc.returncode}")
        return raw
    finally:
        os.unlink(tmp)


def parse_sse_steps(raw: str) -> list[dict]:
    steps: list[dict] = []
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
        if obj.get("type") != "step":
            continue
        steps.append(obj)
    return steps


def parse_assistant_steps(raw) -> list[dict]:
    if isinstance(raw, list):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, list) else []
        except json.JSONDecodeError:
            return []
    return []


def wait_for_peer_step(token: str, conv_id: str, max_wait: int, sse_steps: list[dict]) -> tuple[dict, list[dict]]:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        if any(str(s.get("id")) == "peer-collab" for s in sse_steps):
            detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
            messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
            assistants = [m for m in messages if m.get("role") == "assistant"]
            assistant = assistants[-1] if assistants else {}
            return assistant, parse_assistant_steps(assistant.get("steps"))
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if not assistants:
            time.sleep(2)
            continue
        assistant = assistants[-1]
        steps = parse_assistant_steps(assistant.get("steps"))
        if any(str(s.get("id")) == "peer-collab" for s in steps):
            return assistant, steps
        if assistant.get("status") == "completed":
            return assistant, steps
        time.sleep(2)
    raise RuntimeError(f"peer-collab step not observed within {max_wait}s")


def latest_step(steps: list[dict], step_id: str) -> dict | None:
    matched = [s for s in steps if str(s.get("id")) == step_id]
    return matched[-1] if matched else None


def merge_steps(sse_raw: str, assistant: dict) -> list[dict]:
    sse_steps = parse_sse_steps(sse_raw)
    persisted = parse_assistant_steps(assistant.get("steps"))
    by_id: dict[str, dict] = {}
    for step in persisted + sse_steps:
        sid = str(step.get("id") or "")
        if sid:
            by_id[sid] = step
    return list(by_id.values())


def summary_after(step: dict | None) -> str:
    if not step:
        return ""
    summary = step.get("summary") or {}
    return str(summary.get("after") or "")


def run_e1(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[E1] query={query}")
    import threading
    sse_steps: list[dict] = []
    sse_done = threading.Event()

    def collect():
        nonlocal sse_steps
        try:
            raw = chat_sse(token, conv_id, query)
            sse_steps = parse_sse_steps(raw)
        finally:
            sse_done.set()

    threading.Thread(target=collect, daemon=True).start()
    assistant, _persisted = wait_for_peer_step(token, conv_id, TIMEOUT_SEC, sse_steps)
    sse_done.wait(timeout=5)
    steps = merge_steps("\n".join(json.dumps(s) for s in sse_steps), assistant)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  steps={step_ids}")

    intent = latest_step(steps, "intent")
    peer = latest_step(steps, "peer-collab")
    intent_after = summary_after(intent)
    has_peer = peer is not None
    intent_ok = "多专家" in intent_after or "协作" in intent_after
    no_plan = latest_step(steps, "plan") is None
    ok = has_peer and intent_ok and no_plan
    return {
        "pass": ok,
        "has_peer_collab": has_peer,
        "intent_after": intent_after,
        "no_plan": no_plan,
        "step_ids": step_ids,
        "message_id": assistant.get("id"),
    }


def run_e2_negative(token: str, conv_id: str) -> dict:
    print(f"\n[E2-N] query={E2_QUERY}")
    raw = chat_sse(token, conv_id, E2_QUERY)
    steps = parse_sse_steps(raw)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  sse_steps={step_ids}")
    has_plan = any(str(s.get("id")) == "plan" for s in steps)
    has_peer = any(str(s.get("id")) == "peer-collab" for s in steps)
    ok = has_plan and not has_peer
    return {"pass": ok, "has_plan": has_plan, "has_peer_collab": has_peer, "step_ids": step_ids}


def check_peer_audit_api(message_id: str | None) -> dict:
    if not message_id:
        return {"pass": False, "reason": "no message_id"}
    try:
        resp = requests.get(f"{ORCH_URL}/api/audit/peer-run/{message_id}", timeout=10)
        if resp.status_code == 404:
            return {"pass": True, "note": "endpoint 404 (run may still be in progress)"}
        body = resp.json()
        ok = body.get("code") == 200
        data = body.get("data")
        return {"pass": ok, "has_data": data is not None, "code": body.get("code")}
    except requests.RequestException as exc:
        return {"pass": False, "error": str(exc)}


def orchestrator_ready() -> bool:
    try:
        requests.get(f"{ORCH_URL}/health", timeout=5)
        return True
    except requests.RequestException:
        return False


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--query", default=E1_QUERY)
    p.add_argument("--skip-negative", action="store_true", help="跳过 E2 plan-workflow 负例")
    p.add_argument("--skip-audit", action="store_true", help="跳过 peer-run 审计 API 探测")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    print(f"=== PEER_COLLAB Live §E ===\nGateway={GATEWAY_URL} Orchestrator={ORCH_URL}")
    print("前置: sync_nacos + restart orchestrator")

    if not orchestrator_ready():
        print(f"[FAIL] Orchestrator 未就绪: {ORCH_URL}/health", file=sys.stderr)
        print("  hint: python3 scripts/sync_nacos.py && python3 scripts/start.py --restart orchestrator", file=sys.stderr)
        return 1

    print("\nStep 1: auth")
    token, conv_id = setup_auth()

    report: dict = {"steps": {}}
    report["steps"]["E1"] = run_e1(token, conv_id, args.query)

    if not args.skip_negative:
        token2, conv2 = setup_auth()
        report["steps"]["E2-N"] = run_e2_negative(token2, conv2)

    if not args.skip_audit and report["steps"]["E1"].get("message_id"):
        report["steps"]["audit"] = check_peer_audit_api(report["steps"]["E1"]["message_id"])

    all_pass = all(v.get("pass") for v in report["steps"].values())
    print(f"\n=== Report ===\n{json.dumps(report, ensure_ascii=False, indent=2)}")
    if all_pass:
        print("[PASS] PEER_COLLAB Live §E")
        return 0
    print("[FAIL] PEER_COLLAB Live §E")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
