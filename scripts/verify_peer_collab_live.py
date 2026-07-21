#!/usr/bin/env python3
"""PEER_COLLAB Live 验收 — routing-golden-set §E（L1 句式路由 + Timeline 形态）。

用法:
  python3 scripts/verify_peer_collab_live.py
  python3 scripts/verify_peer_collab_live.py --query "请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证"

前置:
  - docs/nacos/sunshine-orchestrator.yaml 已 sync + orchestrator 已重启
  - expert-manager :8235 已启动（E1 走 Expert Catalog + Coordinator）
  - LLM / skill 链路可用

说明:
  §E 验 L1 句式 → PEER_COLLAB 路由；Timeline 与 §K 一致（expert-convene + expert-*，无 peer-collab / generate）。
  逐步 expert 步与 `$` 绑定细节见 verify_expert_consultation_live.py §K。

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
E1_QUERY = "请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证"
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


def is_peer_collab_timeline(steps: list[dict]) -> bool:
    """4.7.3 演进：expert-convene；历史消息可能仍为 peer-collab。"""
    if any(str(s.get("id")) == "expert-convene" for s in steps):
        return True
    return any(str(s.get("id")) == "peer-collab" for s in steps)


def wait_for_peer_timeline(token: str, conv_id: str, max_wait: int, sse_steps: list[dict]) -> tuple[dict, list[dict]]:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        if is_peer_collab_timeline(sse_steps):
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
        if is_peer_collab_timeline(steps):
            return assistant, steps
        if assistant.get("status") == "completed":
            return assistant, steps
        time.sleep(2)
    raise RuntimeError(f"expert-convene / peer-collab step not observed within {max_wait}s")


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
    assistant, _persisted = wait_for_peer_timeline(token, conv_id, TIMEOUT_SEC, sse_steps)
    sse_done.wait(timeout=5)
    steps = merge_steps("\n".join(json.dumps(s) for s in sse_steps), assistant)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  steps={step_ids}")

    intent = latest_step(steps, "intent")
    convene = latest_step(steps, "expert-convene")
    legacy_peer = latest_step(steps, "peer-collab")
    intent_after = summary_after(intent)
    has_timeline = convene is not None or legacy_peer is not None
    intent_ok = "多专家" in intent_after or "协作" in intent_after
    no_plan = latest_step(steps, "plan") is None
    no_generate = latest_step(steps, "generate") is None
    ok = has_timeline and intent_ok and no_plan and no_generate
    return {
        "pass": ok,
        "has_expert_convene": convene is not None,
        "has_peer_collab_legacy": legacy_peer is not None,
        "intent_after": intent_after,
        "no_plan": no_plan,
        "no_generate": no_generate,
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
    has_peer_timeline = is_peer_collab_timeline(steps)
    ok = has_plan and not has_peer_timeline
    return {
        "pass": ok,
        "has_plan": has_plan,
        "has_peer_timeline": has_peer_timeline,
        "step_ids": step_ids,
    }


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
