#!/usr/bin/env python3
"""Phase 2 Live 验收 — react / workflow / react-taskboard / all 套件。

用法:
  python scripts/phase2_agent_demo.py --suite all
  python scripts/phase2_agent_demo.py --suite react
  python scripts/phase2_agent_demo.py --suite workflow
  python scripts/phase2_agent_demo.py --suite react-taskboard
  python scripts/phase2_agent_demo.py --suite all --skip-rag-prep

react-taskboard 前置: agent.execution.react.taskboard.enabled=true（sync_nacos + 重启 orchestrator）
也可单独跑: python scripts/verify_react_taskboard_live.py

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
FIN_LIST = "sdk__sunshine-finance__list_my_expenses"
FINANCE_URL = os.environ.get("FINANCE_URL", "http://ecs4c16g:8710").rstrip("/")
RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))
FINANCE_QUERY = "列出我的待审批报销单（含标题和金额）"
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "ecs4c16g"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}


def seed_finance_for_user(user_id: str) -> None:
    """为新注册用户种子一条 pending 报销 + 财务待办，使 react/workflow finance
    断言（list_my_expenses 工具命中 + 报销关键词）确定性成立。
    此前脚本假设新用户自带种子数据，DB 演进后漂移为 0 命中。"""
    import shutil
    import subprocess as _sp
    mysql = shutil.which("mysql")
    if not mysql:
        print("  [WARN] mysql client 缺失，跳过 finance 种子（finance 断言可能失败）")
        return
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    today = datetime.now().strftime("%Y-%m-%d")
    exp_id = f"exp-seed-{datetime.now():%H%M%S}"
    inbox_id = f"inbox-seed-{datetime.now():%H%M%S}"
    sql = (
        f"INSERT INTO sunshine_biz.fin_expense "
        f"(id,tenant_id,user_id,category,amount,status,occurred_on,remark,created_at,updated_at) "
        f"VALUES ('{exp_id}','default','{user_id}','差旅',128.50,'pending','{today}',"
        f"'种子待审批报销','{now}','{now}');"
        f"INSERT INTO sunshine_biz.fin_inbox "
        f"(id,tenant_id,user_id,title,status,amount,created_at,updated_at) "
        f"VALUES ('{inbox_id}','default','{user_id}','种子待审批报销单','pending',128.50,'{now}','{now}');"
    )
    _sp.run(
        [mysql, "-h", MYSQL["host"], "-P", str(MYSQL["port"]),
         "-u", MYSQL["user"], f"-p{MYSQL['password']}", "-e", sql],
        capture_output=True, text=True)
    print(f"  OK seeded finance pending for user={user_id} exp={exp_id}")


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


def chat_sse(token: str, conv_id: str, query: str, **extra) -> str:
    curl = shutil_which("curl")
    if not curl:
        raise RuntimeError("curl not found (required for SSE sampling)")
    payload = {"content": query, "conversationId": conv_id, **extra}
    payload_json = json.dumps(payload, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload_json)
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


def collect_sse_steps(raw: str) -> list[dict]:
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


def merge_steps(sse_raw: str, assistant: dict) -> list[dict]:
    sse_steps = collect_sse_steps(sse_raw)
    persisted = parse_assistant_steps(assistant.get("steps"))
    if len(persisted) >= len(sse_steps):
        return persisted
    by_id: dict[str, dict] = {}
    for step in sse_steps + persisted:
        sid = str(step.get("id") or "")
        if sid:
            by_id[sid] = step
    return list(by_id.values())


def latest_step(steps: list[dict], step_id: str) -> dict | None:
    matched = [s for s in steps if str(s.get("id")) == step_id]
    return matched[-1] if matched else None


def tasks_item_count(step: dict | None) -> int:
    if not step:
        return 0
    tasks = (step.get("metadata") or {}).get("tasks") or []
    return len(tasks) if isinstance(tasks, list) else 0


def run_react_taskboard(token: str, conv_id: str) -> dict:
    query = "帮我查待审批报销，并对有风险的单据逐条说明原因"
    print(f"\n[react-taskboard] SSE chat preference=react query={query}")
    sse_raw = chat_sse(token, conv_id, query, executionPreference="react")
    assistant = wait_assistant_completed(token, conv_id, 120)
    steps = merge_steps(sse_raw, assistant)
    tasks = latest_step(steps, "tasks")
    plan = latest_step(steps, "plan")
    item_count = tasks_item_count(tasks)
    has_plan_dag = plan is not None and "planId=" in str(plan.get("detail") or "")
    tool_hit = FIN_LIST in sse_raw or any(
        str(s.get("id", "")).startswith("tool-") for s in steps)
    ok = item_count >= 2 and not has_plan_dag
    return {
        "pass": ok,
        "tasks_items": item_count,
        "has_plan_dag": has_plan_dag,
        "tool_hit": tool_hit,
        "step_count": len(steps),
    }


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


# 财务待办 preflight 种子用户（fin_inbox 中持有 pending 数据的 demo 账号）
FINANCE_SEED_USER = os.environ.get(
    "FINANCE_SEED_USER", "64306e36-2d36-4c72-9a10-c6be5ff291a3")


def preflight_finance() -> None:
    pending = requests.get(
        f"{FINANCE_URL}/api/finance/inbox?status=pending",
        headers={"x-user-id": FINANCE_SEED_USER},
        timeout=10).json()
    if pending.get("code") != 200 or not pending.get("data"):
        raise RuntimeError("finance-service has no pending inbox items for seed user")
    print(f"  OK finance pending={len(pending['data'])}")


def preflight_rag() -> None:
    resp = requests.post(
        f"{RAG_URL}/api/rag/search",
        json={"query": "青松假有多少天、怎么申请", "topK": 3},
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
    user_id = (r1.get("data") or {}).get("userId")
    r2 = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    if r2.get("code") != 200 or not r2.get("data", {}).get("token"):
        raise RuntimeError("login failed")
    token = r2["data"]["token"]
    conv_id = conversation_id(auth_json("POST", "/api/conversations", None, token))
    print(f"  OK user={user} conversation={conv_id}")
    if user_id:
        seed_finance_for_user(user_id)
    return token, conv_id


def run_react_finance(token: str, conv_id: str) -> dict:
    print("\n[react-finance] SSE chat")
    sse_raw = chat_sse(token, conv_id, FINANCE_QUERY)
    sse = parse_sse(sse_raw)
    assistant = wait_assistant_completed(token, conv_id, 30)
    steps_json = json.dumps(assistant.get("steps") or "")
    tool_invoked = (FIN_LIST in sse_raw) or (FIN_LIST in steps_json)
    content = str(assistant.get("content") or "") or sse["content"]
    finance_hit = any(x in content for x in ("待审批", "报销", "pending", "exp-"))
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
        ok = ok and any(x in content for x in ("待审批", "报销", "pending", "exp-"))
    return {"pass": ok, "step_count": sse["step_count"], "content_len": len(content)}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--suite", choices=["all", "react", "workflow", "react-taskboard"], default="all")
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
        report["steps"]["wf-knowledge"] = run_workflow_chat(token, conv2, "青松假有多少天、怎么申请", "wf-knowledge")
        conv3 = conversation_id(auth_json("POST", "/api/conversations", None, token))
        report["steps"]["wf-finance-list"] = run_workflow_chat(
            token, conv3, "列出我的待审批报销单", "wf-finance-list",
            expect_tool=FIN_LIST, expect_finance_data=True)
        conv4 = conversation_id(auth_json("POST", "/api/conversations", None, token))
        report["steps"]["wf-finance-smart"] = run_workflow_chat(
            token, conv4, "待审批报销是否合规", "wf-finance-smart", expect_agent=True)

    if args.suite == "react-taskboard":
        conv_tb = conversation_id(auth_json("POST", "/api/conversations", None, token))
        report["steps"]["react-taskboard"] = run_react_taskboard(token, conv_tb)

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
