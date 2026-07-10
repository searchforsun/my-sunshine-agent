#!/usr/bin/env python3
"""ReAct TaskBoard Live 验收 — routing-golden-set §F。

用法:
  python scripts/verify_react_taskboard_live.py
  python scripts/verify_react_taskboard_live.py --query "帮我查待审批报销，并对有风险的单据逐条说明原因"

前置:
  - agent.execution.react.taskboard.enabled=true（Nacos 已 sync + orchestrator 已重启）
  - 财务 / LLM 链路可用

环境变量: GATEWAY_URL, FINANCE_URL, TASKBOARD_TIMEOUT_SEC
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
FINANCE_URL = os.environ.get("FINANCE_URL", "http://ecs4c16g:8710").rstrip("/")
FIN_LIST = "sdk__sunshine-finance__list_finance_messages"
TIMEOUT_SEC = int(os.environ.get("TASKBOARD_TIMEOUT_SEC", "180"))
F1_QUERY = "帮我查待审批报销，并对有风险的单据逐条说明原因"
FN1_QUERY = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论"


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"taskboard_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "TaskBoard"}, None)
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


def preflight_finance() -> None:
    pending = requests.get(f"{FINANCE_URL}/api/finance/messages?status=pending", timeout=10).json()
    if pending.get("code") != 200 or not pending.get("data"):
        raise RuntimeError("finance-service has no pending messages")


def chat_sse(token: str, conv_id: str, query: str, *, preference: str | None = None) -> str:
    import shutil
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body: dict = {"content": query, "conversationId": conv_id}
    if preference:
        body["executionPreference"] = preference
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


def wait_assistant(token: str, conv_id: str, max_wait: int = 120) -> dict:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within {max_wait}s")


def confirm_plan(token: str, approval_token: str) -> bool:
    body = auth_json(
        "POST",
        "/api/chat/confirm-plan",
        {"token": approval_token, "action": "approve"},
        token,
    )
    data = body.get("data") or body
    return data.get("accepted") is True


def wait_for_plan_step(
        token: str, conv_id: str, max_wait: int, sse_steps: list[dict]) -> tuple[dict, list[dict]]:
    """F-N1 边界：plan 步出现即可，不要求整链跑完。"""
    deadline = time.time() + max_wait
    while time.time() < deadline:
        if any(str(s.get("id")) == "plan" for s in sse_steps):
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
        if any(str(s.get("id")) == "plan" for s in steps):
            return assistant, steps
        if assistant.get("status") == "completed":
            return assistant, steps
        time.sleep(2)
    raise RuntimeError(f"plan step not observed within {max_wait}s")


def latest_step(steps: list[dict], step_id: str) -> dict | None:
    matched = [s for s in steps if str(s.get("id")) == step_id]
    return matched[-1] if matched else None


def tasks_item_count(step: dict | None) -> int:
    if not step:
        return 0
    meta = step.get("metadata") or {}
    tasks = meta.get("tasks") or []
    return len(tasks) if isinstance(tasks, list) else 0


def merge_steps(sse_raw: str, assistant: dict) -> list[dict]:
    sse_steps = parse_sse_steps(sse_raw)
    persisted = parse_assistant_steps(assistant.get("steps"))
    if len(persisted) >= len(sse_steps):
        return persisted
    by_id: dict[str, dict] = {}
    for s in sse_steps + persisted:
        sid = str(s.get("id") or "")
        if sid:
            by_id[sid] = s
    return list(by_id.values())


def run_f1(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[F1] query={query}")
    raw = chat_sse(token, conv_id, query, preference="react")
    assistant = wait_assistant(token, conv_id, 120)
    steps = merge_steps(raw, assistant)
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  steps={step_ids}")

    tasks = latest_step(steps, "tasks")
    plan = latest_step(steps, "plan")
    item_count = tasks_item_count(tasks)
    has_plan_dag = plan is not None and (
        "planId=" in str(plan.get("detail") or "")
        or bool((plan.get("metadata") or {}).get("planApproval"))
    )
    tool_hit = FIN_LIST in raw or any(str(s.get("id", "")).startswith("tool-") for s in steps)

    ok = item_count >= 2 and not has_plan_dag
    if not ok and item_count == 0:
        print("  hint: 若始终无 tasks 步，请确认 agent.execution.react.taskboard.enabled=true 且已重启 orchestrator")
    return {
        "pass": ok,
        "tasks_items": item_count,
        "has_plan_dag": has_plan_dag,
        "tool_hit": tool_hit,
        "step_ids": step_ids,
    }


def collect_sse_background(token: str, conv_id: str, query: str, *, preference: str | None = None) -> tuple[list[dict], threading.Event]:
    """后台消费 SSE，主线程可并行轮询会话 steps。"""
    steps: list[dict] = []
    done = threading.Event()
    approved_tokens: set[str] = set()

    def run() -> None:
        try:
            body: dict = {"content": query, "conversationId": conv_id}
            if preference:
                body["executionPreference"] = preference
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                json=body,
                stream=True,
                timeout=TIMEOUT_SEC,
            ) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines(decode_unicode=True):
                    if not line or not line.startswith("data:"):
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
                    if str(obj.get("id")) != "plan":
                        continue
                    pa = (obj.get("metadata") or {}).get("planApproval") or {}
                    approval_token = pa.get("token")
                    if approval_token and pa.get("status") == "awaiting" and approval_token not in approved_tokens:
                        approved_tokens.add(approval_token)
                        ok = confirm_plan(token, approval_token)
                        print(f"  [F-N1] auto approve plan token={approval_token[:8]}... accepted={ok}")
        finally:
            done.set()

    threading.Thread(target=run, daemon=True).start()
    return steps, done


def run_fn1(token: str, conv_id: str) -> dict:
    print(f"\n[F-N1] query={FN1_QUERY}")
    sse_steps, sse_done = collect_sse_background(token, conv_id, FN1_QUERY)
    assistant, persisted = wait_for_plan_step(token, conv_id, 180, sse_steps)
    sse_done.wait(timeout=5)
    steps = merge_steps("", assistant)
    by_id: dict[str, dict] = {str(s.get("id")): s for s in steps if s.get("id")}
    for step in sse_steps + persisted:
        sid = str(step.get("id") or "")
        if sid:
            by_id[sid] = step
    steps = list(by_id.values())
    step_ids = [str(s.get("id")) for s in steps]
    print(f"  steps={step_ids}")

    tasks = latest_step(steps, "tasks")
    plan = latest_step(steps, "plan")
    has_tasks = tasks is not None and tasks_item_count(tasks) > 0
    has_plan = plan is not None
    ok = has_plan and not has_tasks
    return {"pass": ok, "has_plan": has_plan, "has_tasks": has_tasks, "step_ids": step_ids}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--query", default=F1_QUERY)
    p.add_argument("--skip-negative", action="store_true", help="跳过 F-N1 plan-workflow 负例")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    print(f"=== ReAct TaskBoard Live §F ===\nGateway={GATEWAY_URL}")
    print("前置: taskboard.enabled=true + sync_nacos + restart orchestrator")

    print("\nStep 0: preflight")
    preflight_finance()

    print("\nStep 1: auth")
    token, conv_id = setup_auth()

    report = {"steps": {}}
    report["steps"]["F1"] = run_f1(token, conv_id, args.query)

    if not args.skip_negative:
        conv_resp = auth_json("POST", "/api/conversations", None, token)
        conv2 = (conv_resp.get("data") or conv_resp).get("id")
        if not conv2:
            raise RuntimeError(f"create conversation 2 failed: {conv_resp}")
        report["steps"]["F-N1"] = run_fn1(token, conv2)

    failed = [k for k, v in report["steps"].items() if not v.get("pass")]
    print("\n=== Report ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failed:
        raise RuntimeError(f"failed: {failed}")
    print("\n[PASS] react-taskboard §F")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
