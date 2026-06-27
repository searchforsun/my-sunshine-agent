#!/usr/bin/env python3
"""Skill 流程 5B Live 验收 — curl SSE + intent/plan 步断言。

用法:
  python scripts/verify_skill_5b_live.py
  python scripts/verify_skill_5b_live.py --query "@finance-analysis 先查制度再拉待办再分析再润色"
  python scripts/verify_skill_5b_live.py --skip-wait

环境变量:
  GATEWAY_URL（默认 http://ecs4c16g:8000）
  SKILL_5B_TIMEOUT_SEC（默认 180，Planner+DAG 较慢）
  SKILL_MANAGER_URL（可选 preflight，默认 http://ecs4c16g:8225）

断言（routing-golden-set §E3 / E-Live）:
  - intent 步 metadata: skillId + plannerMode=skill-driven
  - plan 步 detail 含 planId=（成功路径有 DAG）
  - 可选 GET /api/execution-plans/{planId} 校验 status
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
SKILL_MANAGER_URL = os.environ.get("SKILL_MANAGER_URL", "http://ecs4c16g:8225").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("SKILL_5B_TIMEOUT_SEC", "180"))
DEFAULT_QUERY = "@finance-analysis 先查制度再拉待办再分析再润色"
DEFAULT_SKILL = "finance-analysis"
PLAN_ID_RE = re.compile(r"planId=([0-9a-f-]{36})", re.I)


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"skill5b_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "Skill5B"}, None)
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


def confirm_plan(token: str, approval_token: str) -> bool:
    body = auth_json(
        "POST",
        "/api/chat/confirm-plan",
        {"token": approval_token, "action": "approve"},
        token,
    )
    data = body.get("data") or body
    return data.get("accepted") is True


def collect_sse_with_plan_approval(token: str, conv_id: str, query: str) -> list[dict]:
    """消费 SSE；若 plan 步进入用户确认则自动 approve。"""
    steps: list[dict] = []
    approved_tokens: set[str] = set()
    error: Exception | None = None
    done = threading.Event()

    def run() -> None:
        nonlocal error
        try:
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                },
                json={"content": query, "conversationId": conv_id},
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
                    status = pa.get("status")
                    if approval_token and status == "awaiting" and approval_token not in approved_tokens:
                        approved_tokens.add(approval_token)
                        ok = confirm_plan(token, approval_token)
                        print(f"[skill-5b] auto approve plan token={approval_token[:8]}... accepted={ok}")
        except Exception as e:
            error = e
        finally:
            done.set()

    threading.Thread(target=run, daemon=True).start()
    deadline = time.time() + TIMEOUT_SEC
    while time.time() < deadline:
        plan = latest_step(steps, "plan")
        if plan and plan.get("lifecycle") == "done" and extract_plan_id(plan.get("detail")):
            break
        if done.is_set():
            break
        time.sleep(0.3)
    if error and not steps:
        raise error
    return steps


def parse_sse_steps(raw: str) -> list[dict]:
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
        if obj.get("type") != "step":
            continue
        steps.append(obj)
    return steps


def latest_step(steps: list[dict], step_id: str) -> dict | None:
    matched = [s for s in steps if str(s.get("id")) == step_id]
    return matched[-1] if matched else None


def extract_plan_id(detail: str | None) -> str | None:
    if not detail:
        return None
    m = PLAN_ID_RE.search(detail)
    return m.group(1) if m else None


def preflight_skill(skill_id: str) -> None:
    try:
        resp = requests.get(f"{SKILL_MANAGER_URL}/api/skills/catalog/index", timeout=10)
        resp.raise_for_status()
        body = resp.json()
        data = body.get("data")
        if isinstance(data, list):
            entries = data
        elif isinstance(body, list):
            entries = body
        else:
            entries = []
        ids = {e.get("id") for e in entries if isinstance(e, dict)}
        if skill_id not in ids:
            raise RuntimeError(f"skill-manager catalog 无 {skill_id!r}，ids={sorted(ids)}")
        print(f"  OK skill-manager catalog 含 {skill_id}")
    except requests.RequestException as e:
        print(f"  WARN skill-manager preflight 跳过: {e}")


def fetch_plan_detail(token: str, plan_id: str) -> dict:
    data = auth_json("GET", f"/api/execution-plans/{plan_id}", None, token)
    return data.get("data") or data


def wait_assistant_completed(token: str, conv_id: str, max_wait_sec: int = 60) -> dict | None:
    deadline = time.time() + max_wait_sec
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or (detail.get("data") or {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") in ("completed", "failed", "interrupted"):
            return assistants[-1]
        time.sleep(2)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Skill 5B Live 验收（SSE + plan 步）")
    parser.add_argument("--query", default=DEFAULT_QUERY, help="E3 验收提示词")
    parser.add_argument("--skill", default=DEFAULT_SKILL, help="期望 skillId")
    parser.add_argument("--skip-preflight", action="store_true")
    parser.add_argument("--skip-wait", action="store_true", help="SSE 结束即返回，不等 assistant 终态")
    parser.add_argument("--no-plan-api", action="store_true", help="不 GET execution-plans 详情")
    args = parser.parse_args()

    print(f"[skill-5b] GATEWAY={GATEWAY_URL} timeout={TIMEOUT_SEC}s")
    print(f"[skill-5b] query={args.query!r} expect skill={args.skill} plannerMode=skill-driven")

    if not args.skip_preflight:
        preflight_skill(args.skill)

    token, conv_id = setup_auth()
    print(f"[skill-5b] conversationId={conv_id}")

    raw = collect_sse_with_plan_approval(token, conv_id, args.query)
    steps = raw
    step_ids = [str(s.get("id")) for s in steps]
    print(f"[skill-5b] SSE steps ({len(steps)}): {step_ids}")

    intent = latest_step(steps, "intent")
    if not intent:
        print("FAIL: 未收到 intent 步", file=sys.stderr)
        return 1

    meta = intent.get("metadata") or {}
    intent_after = (intent.get("summary") or {}).get("after") or intent.get("after")
    print(f"[skill-5b] intent.after: {intent_after}")
    print(f"[skill-5b] intent.metadata: {json.dumps(meta, ensure_ascii=False)}")

    skill_id = meta.get("skillId")
    planner_mode = meta.get("plannerMode")
    routing_reason = meta.get("routingReason")
    errors: list[str] = []

    if skill_id != args.skill:
        errors.append(f"intent.metadata.skillId={skill_id!r} 期望 {args.skill!r}")
    if planner_mode != "skill-driven":
        errors.append(f"intent.metadata.plannerMode={planner_mode!r} 期望 'skill-driven'")
    if routing_reason and "5b-skill-plan" not in str(routing_reason):
        errors.append(f"intent.metadata.routingReason 应含 5b-skill-plan: {routing_reason!r}")
    if intent_after and "动态规划" not in str(intent_after):
        errors.append(f"intent after 应含「动态规划」: {intent_after!r}")

    plan = latest_step(steps, "plan")
    plan_id: str | None = None
    if not plan:
        errors.append("未收到 plan 步（可能 Planner 失败降级 ReAct）")
    else:
        plan_lifecycle = plan.get("lifecycle")
        plan_detail = plan.get("detail")
        plan_after = (plan.get("summary") or {}).get("after") or plan.get("after")
        print(f"[skill-5b] plan.lifecycle={plan_lifecycle} after={plan_after!r}")
        print(f"[skill-5b] plan.detail: {plan_detail}")

        if plan_lifecycle != "done":
            errors.append(f"plan 步未 done: lifecycle={plan_lifecycle!r}")
        plan_id = extract_plan_id(plan_detail)
        if not plan_id:
            if plan_after and "降级" in str(plan_after):
                errors.append(f"Plan 降级 ReAct: {plan_after}")
            else:
                errors.append(f"plan.detail 无 planId=: {plan_detail!r}")
        elif plan_id and "think" in step_ids:
            try:
                if step_ids.index("think") < step_ids.index("plan"):
                    errors.append("成功 5B 路径不应在 plan 前出现 think 步")
            except ValueError:
                pass

    if errors:
        print("FAIL:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print("PASS: intent metadata（skillId + plannerMode）+ plan 步 planId")

    if plan_id and not args.no_plan_api:
        try:
            detail = fetch_plan_detail(token, plan_id)
            status = detail.get("status")
            nodes = (detail.get("validatedPlan") or detail.get("plan") or {}).get("nodes") or []
            print(f"[skill-5b] execution-plan status={status} nodes={len(nodes)}")
            if status in ("rejected", "failed"):
                print(f"WARN: plan 终态 {status} reject={detail.get('rejectReason')}", file=sys.stderr)
                return 2
        except Exception as e:
            print(f"WARN: GET execution-plans 失败: {e}", file=sys.stderr)

    if not args.skip_wait:
        msg = wait_assistant_completed(token, conv_id, max_wait_sec=min(120, TIMEOUT_SEC))
        if msg:
            print(f"[skill-5b] assistant status={msg.get('status')}")
        else:
            print("[skill-5b] WARN: assistant 未在 120s 内终态（Planner 可能仍在执行）", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
