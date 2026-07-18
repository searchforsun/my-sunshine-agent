#!/usr/bin/env python3
"""4.7.6 ReAct spawn_subagent Live 验收 — S1 / S4(soft) / S-HITL / S-WS / S-CANCEL / S5(skip)。

用法:
  python3 scripts/verify_spawn_subagent_live.py
  python3 scripts/verify_spawn_subagent_live.py --suite all
  python3 scripts/verify_spawn_subagent_live.py --suite hitl,workspace,cancel
  python3 scripts/verify_spawn_subagent_live.py --skip-parallel
  python3 scripts/verify_spawn_subagent_live.py --print-prompts

前置:
  - agent.execution.react.subagent.enabled=true（Nacos 已 sync + orchestrator 已重启）
  - RAG / LLM / 沙箱链路可用

环境变量: GATEWAY_URL, SPAWN_SUBAGENT_TIMEOUT_SEC

说明:
  S1      hard：子卡 phase==subagent 或 id 以 subagent- 开头
  S4      soft：诱导两次并行 spawn
  S-HITL  hard：子 Agent 内 sandbox__write 触发 HITL，自动批准后完成
  S-WS    hard：子 Agent 工作区 glob/write/read（writeHitlMode=always 免确认）
  S-CANCEL hard：子卡出现后按 runId cancel → 子卡 paused、主消息 completed（非整轮 interrupted）
  S5      skip：嵌套硬拒由单测覆盖
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
from typing import Any

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
# 子 Agent 内写文件 → 默认 writeHitlMode 触发 HITL（抽屉内确认）
HITL_QUERY = (
    "请调用 spawn_subagent，label=HITL写文件；"
    "prompt 写：只用 sandbox__write 把字符串 spawn-hitl-ok 写入 /workspace/spawn-hitl.txt，"
    "不要用 exec；写完后返回路径与内容确认。"
    "主 Agent 只根据子任务返回作答，不要自己写文件。"
)
# 子 Agent 工作区读写闭环（always 免 HITL，专注沙箱工具）
WORKSPACE_QUERY = (
    "请调用 spawn_subagent，label=工作区操作；"
    "prompt 写：1) 用 sandbox__glob 列出 /workspace；"
    "2) 用 sandbox__write 把字符串 spawn-workspace-ok 写入 /workspace/spawn-ws.txt；"
    "3) 用 sandbox__read 读回该文件并确认内容。不要用 exec。"
    "主 Agent 只根据子任务返回作答，不要自己操作沙箱。"
)
# 诱导较长子任务，便于中途 cancel；主 Agent 接手原 prompt
CANCEL_QUERY = (
    "请调用 spawn_subagent，label=可取消长任务；"
    "prompt 写：用 search_knowledge 深入检索差旅住宿、交通、补贴相关制度，"
    "整理不少于 5 条要点后返回。"
    "若子任务返回「用户已取消」，请主 Agent 自行完成上述检索与要点整理并作答；"
    "不要再次 spawn 同一任务。"
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


def new_conversation(token: str) -> str:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return conv_id


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


class SseCollector:
    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.confirmations: list[dict] = []
        self.confirm_count = 0
        self.generation_id: str | None = None
        self.message_status: str | None = None
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE 未在超时内结束")

    def wait_until(self, predicate, timeout: float) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate(self):
                return
            if self._done.is_set() and self.error:
                raise self.error
            time.sleep(0.2)
        raise TimeoutError("SSE 等待条件超时")

    def parse_line(self, line: str) -> None:
        if not line.startswith("data:"):
            return
        payload = line[5:].strip()
        if not payload:
            return
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            return
        t = obj.get("type")
        if t == "confirmation":
            self.confirmations.append(obj)
        elif t == "step":
            self.steps.append(obj)
        elif t == "generation" and obj.get("id"):
            self.generation_id = str(obj["id"])
        elif t == "message" and obj.get("status"):
            self.message_status = str(obj["status"])


def chat_sse_live(
    token: str,
    conv_id: str,
    query: str,
    *,
    preference: str = "react",
    write_hitl_mode: str | None = None,
    auto_approve: bool = False,
    wait: bool = True,
) -> SseCollector:
    """流式消费；auto_approve 时对多轮 HITL 自动确认（每 token 只确认一次）。
    wait=False 时立即返回 collector，调用方可中途 cancel 后再 wait_done。"""
    collector = SseCollector()
    approved_tokens: set[str] = set()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    def run() -> None:
        try:
            body: dict[str, Any] = {
                "content": query,
                "conversationId": conv_id,
                "executionPreference": preference,
            }
            if write_hitl_mode:
                body["writeHitlMode"] = write_hitl_mode
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers=headers,
                json=body,
                stream=True,
                timeout=(10, TIMEOUT_SEC),
            ) as resp:
                resp.raise_for_status()
                for raw in resp.iter_lines(decode_unicode=True):
                    if raw is None:
                        continue
                    line = raw.strip()
                    if not line.startswith("data:"):
                        continue
                    before = len(collector.confirmations)
                    collector.parse_line(line)
                    if not auto_approve or len(collector.confirmations) <= before:
                        continue
                    conf = collector.confirmations[-1]
                    token_val = conf.get("confirmationToken")
                    if not token_val or token_val in approved_tokens:
                        continue
                    approved_tokens.add(token_val)
                    r = requests.post(
                        f"{GATEWAY_URL}/api/chat/confirm-tool",
                        headers=headers,
                        json={"token": token_val, "approved": True},
                        timeout=30,
                    )
                    r.raise_for_status()
                    collector.confirm_count += 1
                    print(
                        f"  [HITL] approved toolId={conf.get('toolId')} "
                        f"token={str(token_val)[:8]}..."
                    )
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run, daemon=True).start()
    if wait:
        collector.wait_done(TIMEOUT_SEC + 30)
        if collector.error and not collector.steps and not collector.confirmations:
            raise collector.error
    return collector


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


def merge_steps(sse_steps: list[dict], assistant: dict) -> list[dict]:
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


def flatten_step_ids(steps: list[dict]) -> list[str]:
    out: list[str] = []

    def walk(items: list[dict]) -> None:
        for s in items:
            sid = str(s.get("id") or "")
            if sid:
                out.append(sid)
            subs = s.get("subSteps") or []
            if isinstance(subs, list) and subs:
                walk(subs)

    walk(steps)
    return out


def has_sandbox_write(ids: list[str]) -> bool:
    return any("sandbox__write" in x or "sandbox__edit" in x for x in ids)


def has_sandbox_tool(ids: list[str]) -> bool:
    return any("sandbox__" in x for x in ids)


def main_level_think_ids(steps: list[dict]) -> list[str]:
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
    steps = merge_steps(parse_sse_steps(raw), assistant)
    step_ids = [str(s.get("id")) for s in steps]
    sub_cards = collect_subagent_steps(steps)
    think_ids = main_level_think_ids(steps)
    print(f"  steps={step_ids}")
    print(f"  subagent_cards={len(sub_cards)} think_main={think_ids}")

    hard_ok = len(sub_cards) >= 1
    if not hard_ok:
        print(
            "  hint: 若无 subagent-* 步，请确认 agent.execution.react.subagent.enabled=true "
            "且模型实际调用了 spawn_subagent（可查 llm-gateway toolCalls=）"
        )
    return {
        "pass": hard_ok,
        "soft_pass": hard_ok,
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
    steps = merge_steps(parse_sse_steps(raw), assistant)
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


def run_hitl(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S-HITL] query={query}")
    coll = chat_sse_live(
        token, conv_id, query, preference="react", auto_approve=True,
    )
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(coll.steps, assistant)
    sub_cards = collect_subagent_steps(steps)
    flat_ids = flatten_step_ids(steps)
    wrote = has_sandbox_write(flat_ids)
    hitl_ok = coll.confirm_count >= 1 or len(coll.confirmations) >= 1
    # 子卡 + 写工具 + 出现过 confirmation（或已 auto approve）
    hard_ok = len(sub_cards) >= 1 and wrote and hitl_ok
    print(
        f"  subagent={len(sub_cards)} sandbox_write={wrote} "
        f"confirmations={len(coll.confirmations)} approved={coll.confirm_count}"
    )
    print(f"  flat_toolish={ [x for x in flat_ids if 'sandbox' in x or 'tool-' in x][:12] }")
    if not hard_ok:
        print(
            "  hint: 需子 Agent 内调用 sandbox__write，且默认 HITL 弹出 confirmation；"
            "可查 SSE type:confirmation / 子卡 subSteps"
        )
    return {
        "pass": hard_ok,
        "subagent_count": len(sub_cards),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "sandbox_write": wrote,
        "confirm_count": coll.confirm_count,
        "confirmation_seen": len(coll.confirmations),
        "flat_ids_sample": flat_ids[:24],
        "content_preview": (assistant.get("content") or "")[:200],
    }


def run_workspace(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S-WS] query={query}")
    # always：写操作免 HITL，专注工作区工具闭环
    coll = chat_sse_live(
        token,
        conv_id,
        query,
        preference="react",
        write_hitl_mode="always",
        auto_approve=False,
    )
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(coll.steps, assistant)
    sub_cards = collect_subagent_steps(steps)
    flat_ids = flatten_step_ids(steps)
    wrote = has_sandbox_write(flat_ids)
    sandbox_any = has_sandbox_tool(flat_ids)
    hard_ok = len(sub_cards) >= 1 and wrote and sandbox_any
    print(
        f"  subagent={len(sub_cards)} sandbox_any={sandbox_any} "
        f"sandbox_write={wrote} confirmations={len(coll.confirmations)}"
    )
    print(f"  flat_sandbox={ [x for x in flat_ids if 'sandbox' in x][:12] }")
    if not hard_ok:
        print(
            "  hint: 需子 Agent 调用 sandbox__write（及 ideally glob/read）；"
            "writeHitlMode=always 应无 confirmation"
        )
    return {
        "pass": hard_ok,
        "subagent_count": len(sub_cards),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "sandbox_write": wrote,
        "sandbox_any": sandbox_any,
        "confirmation_seen": len(coll.confirmations),
        "flat_ids_sample": flat_ids[:24],
        "content_preview": (assistant.get("content") or "")[:200],
    }


def latest_subagent_run_id(steps: list[dict]) -> str | None:
    for step in reversed(steps):
        if not is_subagent_step(step):
            continue
        sid = str(step.get("id") or "")
        if sid.startswith("subagent-"):
            return sid[len("subagent-"):]
    return None


def run_cancel(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S-CANCEL] query={query}")
    coll = chat_sse_live(token, conv_id, query, preference="react", wait=False)
    try:
        coll.wait_until(
            lambda c: bool(c.generation_id)
            and any(is_subagent_step(s) for s in c.steps),
            timeout=min(TIMEOUT_SEC, 120),
        )
    except TimeoutError as e:
        coll.wait_done(5)
        print(f"  hint: 未在时限内出现 generation+subagent：{e}")
        return {
            "pass": False,
            "error": str(e),
            "generation_id": coll.generation_id,
            "subagent_ids": [str(s.get("id")) for s in collect_subagent_steps(coll.steps)],
        }

    run_id = latest_subagent_run_id(coll.steps)
    gen_id = coll.generation_id
    print(f"  generationId={gen_id} runId={run_id}")
    if not run_id or not gen_id:
        coll.wait_done(TIMEOUT_SEC)
        return {"pass": False, "error": "missing generationId or runId"}

    cancel_resp = auth_json(
        "POST",
        f"/api/generations/{gen_id}/subagents/{run_id}/cancel",
        None,
        token,
    )
    cancel_status = (cancel_resp.get("data") or cancel_resp).get("status") or cancel_resp.get("status")
    print(f"  cancel_api status={cancel_status} raw={cancel_resp}")

    coll.wait_done(TIMEOUT_SEC + 30)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(coll.steps, assistant)
    sub_cards = collect_subagent_steps(steps)
    paused = [
        s for s in sub_cards
        if str(s.get("lifecycle") or "") in ("paused", "terminated")
    ]
    msg_status = str(assistant.get("status") or coll.message_status or "")
    # 主轮须 completed（非整轮 interrupted）；至少一张子卡 paused
    hard_ok = (
        msg_status == "completed"
        and len(sub_cards) >= 1
        and len(paused) >= 1
        and cancel_status in ("CANCELLED", "NOT_FOUND", None)
    )
    # NOT_FOUND 可接受：子任务极快结束；此时若仍 completed 且无 interrupted 也算 soft
    if cancel_status == "NOT_FOUND" and msg_status == "completed" and not paused:
        hard_ok = False
        print("  hint: cancel 时子任务已结束，未能验证 per-sub cancel；可重试")
    print(
        f"  msg_status={msg_status} subagent={len(sub_cards)} paused={len(paused)} "
        f"sse_msg={coll.message_status}"
    )
    print(f"  content_preview={(assistant.get('content') or '')[:200]}")
    if not hard_ok:
        print(
            "  hint: 期望 cancel API 后子卡 lifecycle=paused，主消息 status=completed；"
            "勿调用整轮 /generations/{id}/cancel"
        )
    return {
        "pass": hard_ok,
        "cancel_status": cancel_status,
        "message_status": msg_status,
        "subagent_count": len(sub_cards),
        "paused_count": len(paused),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "generation_id": gen_id,
        "run_id": run_id,
        "content_preview": (assistant.get("content") or "")[:200],
    }


def parse_args():
    p = argparse.ArgumentParser(description="4.7.6 spawn_subagent Live 验收")
    p.add_argument("--query", default=S1_QUERY, help="S1 诱导 query")
    p.add_argument(
        "--suite",
        default="all",
        help="用例：all | s1,s4,hitl,workspace,cancel（逗号分隔）",
    )
    p.add_argument("--skip-parallel", action="store_true", help="跳过 S4 并行 soft 用例")
    p.add_argument("--print-prompts", action="store_true", help="只打印提示词后退出")
    return p.parse_args()


def parse_suite(raw: str, skip_parallel: bool) -> list[str]:
    if raw.strip().lower() == "all":
        items = ["s1", "s4", "hitl", "workspace", "cancel"]
    else:
        items = [x.strip().lower() for x in raw.split(",") if x.strip()]
    if skip_parallel:
        items = [x for x in items if x != "s4"]
    return items


def main() -> int:
    args = parse_args()
    if args.print_prompts:
        print("=== spawn_subagent Live 提示词 ===\n")
        print("[S1 制度检索]\n" + S1_QUERY + "\n")
        print("[S4 并行]\n" + S4_QUERY + "\n")
        print("[S-HITL]\n" + HITL_QUERY + "\n")
        print("[S-WS 工作区]\n" + WORKSPACE_QUERY + "\n")
        print("[S-CANCEL]\n" + CANCEL_QUERY + "\n")
        return 0

    suite = parse_suite(args.suite, args.skip_parallel)
    print(f"=== ReAct spawn_subagent Live ===\nGateway={GATEWAY_URL}\nsuite={suite}")
    print("前置: subagent.enabled=true + sync_nacos + restart orchestrator")
    print("[S5] nesting: SKIP (unit-tested)")

    print("\nStep 1: auth")
    token, conv_id = setup_auth()

    report: dict = {"steps": {}, "skipped": ["S5"], "prompts": {
        "S1": S1_QUERY,
        "S4": S4_QUERY,
        "S-HITL": HITL_QUERY,
        "S-WS": WORKSPACE_QUERY,
        "S-CANCEL": CANCEL_QUERY,
    }}

    if "s1" in suite:
        report["steps"]["S1"] = run_s1(token, conv_id, args.query)

    if "s4" in suite:
        report["steps"]["S4"] = run_s4(token, new_conversation(token), S4_QUERY)

    if "hitl" in suite:
        report["steps"]["S-HITL"] = run_hitl(token, new_conversation(token), HITL_QUERY)

    if "workspace" in suite:
        report["steps"]["S-WS"] = run_workspace(token, new_conversation(token), WORKSPACE_QUERY)

    if "cancel" in suite:
        report["steps"]["S-CANCEL"] = run_cancel(token, new_conversation(token), CANCEL_QUERY)

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
