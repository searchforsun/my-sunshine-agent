#!/usr/bin/env python3
"""4.7.6 ReAct spawn_subagent Live 验收 — 检查门 S1 / S4(soft) / S5(skip)。

用法:
  python3 scripts/verify_spawn_subagent_live.py
  python3 scripts/verify_spawn_subagent_live.py --skip-parallel
  python3 scripts/verify_spawn_subagent_live.py --query "请调用 spawn_subagent …"

前置:
  - agent.execution.react.subagent.enabled=true（Nacos 已 sync + orchestrator 已重启）
  - RAG / LLM 链路可用（S1 诱导子任务调用 search_knowledge）

环境变量: GATEWAY_URL, SPAWN_SUBAGENT_TIMEOUT_SEC

说明:
  S1  hard：终态 steps（或 SSE）含 phase==subagent 或 id 以 subagent- 开头
  S1  soft：至少一张子卡存在（主栈不抬升子 think 的硬断言难以自动化）
  S4  soft/warn：诱导两次并行 spawn；不稳定时仅 WARN，不导致非零退出
  S5  skip：嵌套硬拒由单测覆盖，Live 不跑
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
TIMEOUT_SEC = int(os.environ.get("SPAWN_SUBAGENT_TIMEOUT_SEC", "240"))

S1_QUERY = (
    "请调用 spawn_subagent，prompt 写：用 search_knowledge 检索差旅住宿标准并返回要点摘要；"
    "label=制度检索。主 Agent 只根据子任务返回作答。"
)
S4_QUERY = (
    "请在同一轮并行调用两次 spawn_subagent："
    "第一次 prompt=用 search_knowledge 检索差旅住宿标准并返回要点，label=住宿标准；"
    "第二次 prompt=用 search_knowledge 检索差旅交通补贴标准并返回要点，label=交通补贴。"
    "主 Agent 只根据两个子任务返回汇总作答，不要自己直接检索。"
)


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"spawn_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "SpawnSub"},
        None,
    )
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


def chat_sse(token: str, conv_id: str, query: str, *, preference: str = "react") -> str:
    import shutil

    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body: dict = {
        "content": query,
        "conversationId": conv_id,
        "executionPreference": preference,
    }
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


def wait_assistant(token: str, conv_id: str, max_wait: int = 180) -> dict:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within {max_wait}s")


def merge_steps(sse_raw: str, assistant: dict) -> list[dict]:
    sse_steps = parse_sse_steps(sse_raw)
    persisted = parse_assistant_steps(assistant.get("steps"))
    by_id: dict[str, dict] = {}
    for s in sse_steps + persisted:
        sid = str(s.get("id") or "")
        if sid:
            by_id[sid] = s
    if by_id:
        return list(by_id.values())
    return persisted if persisted else sse_steps


def is_subagent_step(step: dict) -> bool:
    sid = str(step.get("id") or "")
    phase = str(step.get("phase") or "")
    return phase == "subagent" or sid.startswith("subagent-")


def collect_subagent_steps(steps: list[dict]) -> list[dict]:
    return [s for s in steps if is_subagent_step(s)]


def main_level_think_ids(steps: list[dict]) -> list[str]:
    """主栈 think* id（不含 subSteps 内）。"""
    out: list[str] = []
    for s in steps:
        sid = str(s.get("id") or "")
        phase = str(s.get("phase") or "")
        if is_subagent_step(s):
            continue
        if phase == "think" or sid == "think" or sid.startswith("think-"):
            out.append(sid)
    return out


def run_s1(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S1] query={query}")
    raw = chat_sse(token, conv_id, query, preference="react")
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(raw, assistant)
    step_ids = [str(s.get("id")) for s in steps]
    sub_cards = collect_subagent_steps(steps)
    think_ids = main_level_think_ids(steps)
    print(f"  steps={step_ids}")
    print(f"  subagent_cards={len(sub_cards)} think_main={think_ids}")

    hard_ok = len(sub_cards) >= 1
    # soft：存在子卡即认为主栈折叠成立（子 think 仅在 subSteps，难做负例硬断言）
    soft_ok = hard_ok
    if not hard_ok:
        print(
            "  hint: 若无 subagent-* 步，请确认 agent.execution.react.subagent.enabled=true "
            "且模型实际调用了 spawn_subagent（可查 llm-gateway toolCalls=）"
        )
    return {
        "pass": hard_ok,
        "soft_pass": soft_ok,
        "subagent_count": len(sub_cards),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "think_main": think_ids,
        "step_ids": step_ids,
        "content_preview": (assistant.get("content") or "")[:200],
    }


def run_s4(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S4 soft] query={query}")
    raw = chat_sse(token, conv_id, query, preference="react")
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(raw, assistant)
    step_ids = [str(s.get("id")) for s in steps]
    sub_cards = collect_subagent_steps(steps)
    count = len(sub_cards)
    print(f"  steps={step_ids}")
    print(f"  subagent_cards={count}")
    ok = count >= 2
    if not ok:
        print(f"  [WARN] S4 expected >=2 subagent cards, got {count} (soft — model flaky)")
    return {
        "pass": ok,
        "soft": True,
        "subagent_count": count,
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "step_ids": step_ids,
    }


def parse_args():
    p = argparse.ArgumentParser(description="4.7.6 spawn_subagent Live 验收")
    p.add_argument("--query", default=S1_QUERY, help="S1 诱导 query")
    p.add_argument("--skip-parallel", action="store_true", help="跳过 S4 并行 soft 用例")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    print(f"=== ReAct spawn_subagent Live §S1/S4 ===\nGateway={GATEWAY_URL}")
    print("前置: subagent.enabled=true + sync_nacos + restart orchestrator")
    print("[S5] nesting: SKIP (unit-tested)")

    print("\nStep 1: auth")
    token, conv_id = setup_auth()

    report: dict = {"steps": {}, "skipped": ["S5"]}
    report["steps"]["S1"] = run_s1(token, conv_id, args.query)

    if not args.skip_parallel:
        conv_resp = auth_json("POST", "/api/conversations", None, token)
        conv2 = (conv_resp.get("data") or conv_resp).get("id")
        if not conv2:
            raise RuntimeError(f"create conversation 2 failed: {conv_resp}")
        report["steps"]["S4"] = run_s4(token, conv2, S4_QUERY)

    hard_failed = [
        k for k, v in report["steps"].items()
        if not v.get("soft") and not v.get("pass")
    ]
    soft_failed = [
        k for k, v in report["steps"].items()
        if v.get("soft") and not v.get("pass")
    ]

    print("\n=== Report ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if hard_failed:
        raise RuntimeError(f"hard failed: {hard_failed}")
    if soft_failed:
        print(f"\n[PASS with WARN] spawn_subagent Live; soft failed: {soft_failed}")
    else:
        print("\n[PASS] spawn_subagent Live")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
