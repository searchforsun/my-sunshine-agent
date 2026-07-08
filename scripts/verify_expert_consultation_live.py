#!/usr/bin/env python3
"""多专家协作 Live 验收 — routing-golden-set §K。

用法:
  python3 scripts/verify_expert_consultation_live.py
  python3 scripts/verify_expert_consultation_live.py --case K-L1

前置:
  - expert-manager :8235 + orchestrator :8200 已启动
  - docs/nacos 已 sync
  - MySQL sunshine_expert 种子专家 policy / finance / compliance / legal

环境变量: GATEWAY_URL, EXPERT_CONSULT_TIMEOUT_SEC, ORCHESTRATOR_URL, EXPERT_MANAGER_URL
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
ORCH_URL = os.environ.get("ORCHESTRATOR_URL", "http://127.0.0.1:8200").rstrip("/")
EXPERT_URL = os.environ.get("EXPERT_MANAGER_URL", "http://127.0.0.1:8235").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("EXPERT_CONSULT_TIMEOUT_SEC", "300"))
K_L1_QUERY = "$policy-expert $finance-expert 这笔报销是否合规"
K_L2_QUERY = "#finance-smart $policy-expert 是否合规"
K_L3_QUERY = "待审批报销是否合规"


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"expert_{uuid.uuid4().hex[:12]}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "Expert"}, None)
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


def chat_sse(token: str, conv_id: str, query: str, execution_preference: str | None = None) -> str:
    import shutil
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body: dict = {"content": query, "conversationId": conv_id}
    if execution_preference:
        body["executionPreference"] = execution_preference
    payload = json.dumps(body, ensure_ascii=False)
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
        if obj.get("type") == "step":
            steps.append(obj)
    return steps


def parse_sse_step_deltas(raw: str) -> list[dict]:
    deltas: list[dict] = []
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
        if obj.get("type") == "step_delta":
            deltas.append(obj)
    return deltas


def expert_speak_result_deltas(raw: str) -> list[dict]:
    return [
        d for d in parse_sse_step_deltas(raw)
        if str(d.get("stepId", "")).startswith("expert-")
        and d.get("stepId") != "expert-convene"
        and d.get("channel") == "result"
    ]


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


def merge_step_lists(*groups: list[dict]) -> list[dict]:
    by_id: dict[str, dict] = {}
    for steps in groups:
        for step in steps:
            sid = str(step.get("id") or "")
            if sid:
                by_id[sid] = step
    return list(by_id.values())


def wait_for_steps(token: str, conv_id: str, predicate, max_wait: int, sse_steps: list[dict]) -> tuple[dict, list[dict]]:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        merged = merge_step_lists(sse_steps)
        if predicate(merged):
            detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
            messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
            assistants = [m for m in messages if m.get("role") == "assistant"]
            assistant = assistants[-1] if assistants else {}
            return assistant, merge_step_lists(parse_assistant_steps(assistant.get("steps")), merged)
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if not assistants:
            time.sleep(2)
            continue
        assistant = assistants[-1]
        steps = parse_assistant_steps(assistant.get("steps"))
        merged = merge_step_lists(steps, sse_steps)
        if predicate(merged):
            return assistant, merged
        if assistant.get("status") == "completed":
            return assistant, merged
        time.sleep(2)
    raise RuntimeError(f"steps predicate not met within {max_wait}s")


def merge_steps(sse_raw: str, assistant: dict, sse_steps: list[dict] | None = None) -> list[dict]:
    from_sse = parse_sse_steps(sse_raw) if sse_raw else (sse_steps or [])
    persisted = parse_assistant_steps(assistant.get("steps"))
    return merge_step_lists(persisted, from_sse)


def expert_speak_steps(steps: list[dict]) -> list[dict]:
    return [s for s in steps if str(s.get("id", "")).startswith("expert-") and s.get("id") != "expert-convene"]


def run_k_l1(token: str, conv_id: str) -> dict:
    print(f"\n[K-L1] query={K_L1_QUERY}")
    import threading
    sse_steps: list[dict] = []
    sse_raw_holder: list[str] = [""]
    sse_done = threading.Event()

    def collect():
        try:
            raw = chat_sse(token, conv_id, K_L1_QUERY)
            sse_raw_holder[0] = raw
            sse_steps.extend(parse_sse_steps(raw))
        finally:
            sse_done.set()

    threading.Thread(target=collect, daemon=True).start()

    def ready(steps: list[dict]) -> bool:
        return any(s.get("id") == "expert-convene" for s in steps) and len(expert_speak_steps(steps)) >= 1

    assistant, _ = wait_for_steps(token, conv_id, ready, TIMEOUT_SEC, sse_steps)
    sse_done.wait(timeout=TIMEOUT_SEC)
    steps = merge_steps(sse_raw_holder[0], assistant, sse_steps)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  steps={step_ids}")
    speaks = expert_speak_steps(steps)
    speak_deltas = expert_speak_result_deltas(sse_raw_holder[0])
    speak_results = [str(s.get("result") or "") for s in speaks if s.get("lifecycle") == "done"]
    min_result_len = min((len(r) for r in speak_results), default=0)
    print(f"  expert_speak_deltas={len(speak_deltas)}")
    print(f"  expert_speak_min_result_len={min_result_len}")
    ok = (
        any(s.get("id") == "expert-convene" for s in steps)
        and len(speaks) >= 2
        and len(speak_deltas) >= 2
        and min_result_len >= 200
        and not any(s.get("id") == "plan" for s in steps)
        and not any(s.get("id") == "generate" for s in steps)
        and not any(str(s.get("id", "")).startswith("think") for s in steps)
        and not any(s.get("id") == "peer-collab" for s in steps)
    )
    return {
        "pass": ok,
        "step_ids": step_ids,
        "speak_count": len(speaks),
        "speak_delta_count": len(speak_deltas),
        "message_id": assistant.get("id"),
    }


def run_k_l2(token: str, conv_id: str) -> dict:
    print(f"\n[K-L2] query={K_L2_QUERY}")
    raw = chat_sse(token, conv_id, K_L2_QUERY)
    steps = parse_sse_steps(raw)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  sse_steps={step_ids}")
    has_plan = any(str(s.get("id")) == "plan" for s in steps)
    has_expert = any(str(s.get("id")) == "expert-convene" for s in steps)
    ok = has_plan and not has_expert
    return {"pass": ok, "has_plan": has_plan, "has_expert_convene": has_expert, "step_ids": step_ids}


def run_k_l3(token: str, conv_id: str) -> dict:
    print(f"\n[K-L3] preference=peer-collab query={K_L3_QUERY}")
    import threading
    sse_steps: list[dict] = []
    sse_raw_holder: list[str] = [""]
    sse_done = threading.Event()

    def collect():
        try:
            raw = chat_sse(token, conv_id, K_L3_QUERY, "peer-collab")
            sse_raw_holder[0] = raw
            sse_steps.extend(parse_sse_steps(raw))
        finally:
            sse_done.set()

    threading.Thread(target=collect, daemon=True).start()

    def ready(steps: list[dict]) -> bool:
        return any(s.get("id") == "expert-convene" for s in steps)

    assistant, _ = wait_for_steps(token, conv_id, ready, TIMEOUT_SEC, sse_steps)
    sse_done.wait(timeout=TIMEOUT_SEC)
    steps = merge_steps(sse_raw_holder[0], assistant, sse_steps)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  steps={step_ids}")
    ok = (
        any(s.get("id") == "expert-convene" for s in steps)
        and not any(s.get("id") == "plan" for s in steps)
    )
    return {"pass": ok, "step_ids": step_ids}


def run_k_l4() -> dict:
    print("\n[K-L4] expert catalog index")
    try:
        resp = requests.get(f"{EXPERT_URL}/api/experts/catalog/index", timeout=10)
        body = resp.json()
        data = body.get("data") or []
        ok = body.get("code") == 200 and len(data) >= 2
        print(f"  count={len(data)}")
        return {"pass": ok, "count": len(data)}
    except requests.RequestException as exc:
        return {"pass": False, "error": str(exc)}


def services_ready() -> bool:
    try:
        requests.get(f"{ORCH_URL}/health", timeout=5)
        requests.get(f"{EXPERT_URL}/health", timeout=5)
        return True
    except requests.RequestException:
        return False


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--case", choices=["K-L1", "K-L2", "K-L3", "K-L4", "all"], default="all")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    print(f"=== Expert Consultation Live §K ===\nGateway={GATEWAY_URL}")
    if not services_ready():
        print("[FAIL] orchestrator 或 expert-manager 未就绪", file=sys.stderr)
        return 1

    report: dict = {"steps": {}}
    if args.case in ("all", "K-L4"):
        report["steps"]["K-L4"] = run_k_l4()
    if args.case == "K-L4":
        all_pass = report["steps"]["K-L4"].get("pass")
        print(f"\n=== Report ===\n{json.dumps(report, ensure_ascii=False, indent=2)}")
        return 0 if all_pass else 1

    token, conv_id = setup_auth()
    if args.case in ("all", "K-L1"):
        report["steps"]["K-L1"] = run_k_l1(token, conv_id)
    elif args.case == "K-L2":
        report["steps"]["K-L2"] = run_k_l2(token, conv_id)
    elif args.case == "K-L3":
        report["steps"]["K-L3"] = run_k_l3(token, conv_id)

    if args.case == "all":
        token2, conv2 = setup_auth()
        report["steps"]["K-L2"] = run_k_l2(token2, conv2)
        token3, conv3 = setup_auth()
        report["steps"]["K-L3"] = run_k_l3(token3, conv3)

    all_pass = all(v.get("pass") for v in report["steps"].values())
    print(f"\n=== Report ===\n{json.dumps(report, ensure_ascii=False, indent=2)}")
    if all_pass:
        print("[PASS] Expert Consultation Live §K")
        return 0
    print("[FAIL] Expert Consultation Live §K")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
