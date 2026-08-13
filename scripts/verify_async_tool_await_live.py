#!/usr/bin/env python3
"""异步长工具 + await_tool_run Live — S-EXEC / S-SPAWN。

用法:
  python3 scripts/verify_async_tool_await_live.py
  python3 scripts/verify_async_tool_await_live.py --suite exec,spawn
  python3 scripts/verify_async_tool_await_live.py --print-prompts

前置:
  - agent.execution.react.async-tool.enabled=true（sync_nacos + restart orchestrator）
  - mode-overlay.react 含 【AsyncTool · background + await_tool_run】（resource DB + restart）
  - 沙箱 / LLM 可用

环境变量: GATEWAY_URL, ASYNC_TOOL_AWAIT_TIMEOUT_SEC
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
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("ASYNC_TOOL_AWAIT_TIMEOUT_SEC", "300"))

# 诱导明确 background=true + await_tool_run；禁止同步死等 sleep
EXEC_QUERY = (
    "请严格按以下步骤执行（禁止同步空等）："
    "1) 调用 sandbox__exec：command 必须是 `sleep 45 && echo async-exec-ok`，"
    "且必须传 background=true（立即返回 runId，勿省略）；"
    "2) 若工具返回 status=running 与 runId，立即调用 await_tool_run(runId=该runId, timeout_sec=60) "
    "等待终态；若仍 running 可再 await 一次（最多 3 次）；"
    "3) 根据 await 终态结果（done）向用户确认输出含 async-exec-ok。"
    "禁止用不带 background 的 sandbox__exec 同步 sleep；禁止假装已完成。"
)
SPAWN_QUERY = (
    "请严格按以下步骤执行（禁止同步空等）："
    "1) 调用 spawn_subagent：label=异步子任务；必须传 background=true；"
    "prompt 写：只用 echo 或极短回复输出字符串 async-spawn-ok，不要检索、不要长思考；"
    "2) 若返回 status=running 与 runId，立即调用 await_tool_run(runId=该runId, timeout_sec=90) "
    "等待子任务终态；若仍 running 可再 await（最多 3 次）；"
    "3) 主 Agent 只根据 await 终态/子任务返回作答，确认含 async-spawn-ok。"
    "禁止 background=false 或省略 background 导致同步堵死；禁止不 await 就假装完成。"
)


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"ata_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "AsyncToolAwait"},
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


class SseCollector:
    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.generation_id: str | None = None
        self.message_status: str | None = None
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE 未在超时内结束")


def chat_sse_live(token: str, conv_id: str, query: str) -> SseCollector:
    collector = SseCollector()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    def run() -> None:
        try:
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers=headers,
                json={
                    "content": query,
                    "conversationId": conv_id,
                    "executionPreference": "fast",
                    "writeHitlMode": "always",
                },
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
                    payload = line[5:].strip()
                    if not payload:
                        continue
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    t = obj.get("type")
                    if t == "generation" and obj.get("id"):
                        collector.generation_id = str(obj["id"])
                    elif t == "step":
                        collector.steps.append(obj)
                    elif t == "message" and obj.get("status"):
                        collector.message_status = str(obj["status"])
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run, daemon=True).start()
    collector.wait_done(TIMEOUT_SEC + 30)
    if collector.error and not collector.steps:
        raise collector.error
    return collector


def wait_assistant(token: str, conv_id: str, max_wait: int | None = None) -> dict:
    deadline = time.time() + (max_wait if max_wait is not None else min(TIMEOUT_SEC, 280))
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within wait")


def parse_assistant_steps(raw: Any) -> list[dict]:
    if isinstance(raw, list):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, list) else []
        except json.JSONDecodeError:
            return []
    return []


def _lifecycle_rank(step: dict) -> int:
    lc = str(step.get("lifecycle") or "")
    after = ""
    summary = step.get("summary") or {}
    if isinstance(summary, dict):
        after = str(summary.get("after") or "").strip()
    if lc == "paused" and after:
        return 3
    if lc in ("done", "error", "skipped", "terminated"):
        return 2
    if lc == "paused":
        return 1
    if lc == "running":
        return 0
    return -1


def merge_steps(sse_steps: list[dict], assistant: dict) -> list[dict]:
    persisted = parse_assistant_steps(assistant.get("steps"))
    by_id: dict[str, dict] = {}
    for s in sse_steps + persisted:
        sid = str(s.get("id") or "")
        if not sid:
            continue
        prev = by_id.get(sid)
        if prev is None or _lifecycle_rank(s) >= _lifecycle_rank(prev):
            by_id[sid] = s
    if by_id:
        return list(by_id.values())
    return persisted if persisted else sse_steps


def step_blob(step: dict) -> str:
    parts: list[str] = []
    for key in ("result", "expandDetail", "detail"):
        val = step.get(key)
        if isinstance(val, str) and val.strip():
            parts.append(val)
    summary = step.get("summary")
    if isinstance(summary, dict):
        for key in ("after", "active", "before"):
            val = summary.get(key)
            if isinstance(val, str) and val.strip():
                parts.append(val)
    meta = step.get("metadata")
    if isinstance(meta, dict):
        try:
            parts.append(json.dumps(meta, ensure_ascii=False))
        except (TypeError, ValueError):
            pass
    return "\n".join(parts)


def flatten_blobs(steps: list[dict]) -> str:
    chunks: list[str] = []

    def walk(items: list[dict]) -> None:
        for s in items:
            chunks.append(step_blob(s))
            subs = s.get("subSteps") or []
            if isinstance(subs, list) and subs:
                walk(subs)

    walk(steps)
    return "\n".join(chunks)


def is_tool_step(step: dict, tool_name: str) -> bool:
    sid = str(step.get("id") or "")
    if not sid.startswith("tool-"):
        return False
    tool = sid[len("tool-") :].split("@")[0]
    return tool == tool_name


def collect_tool_steps(steps: list[dict], tool_name: str) -> list[dict]:
    out: list[dict] = []

    def walk(items: list[dict]) -> None:
        for s in items:
            if is_tool_step(s, tool_name):
                out.append(s)
            subs = s.get("subSteps") or []
            if isinstance(subs, list) and subs:
                walk(subs)

    walk(steps)
    return out


def is_subagent_step(step: dict) -> bool:
    sid = str(step.get("id") or "")
    phase = str(step.get("phase") or "")
    return phase == "subagent" or sid.startswith("subagent-")


def collect_subagent_steps(steps: list[dict]) -> list[dict]:
    return [s for s in steps if is_subagent_step(s)]


def blob_has_running_ack(blob: str) -> bool:
    if not blob:
        return False
    if '"status"' not in blob and "status" not in blob:
        return False
    has_running = bool(re.search(r'"status"\s*:\s*"running"', blob)) or "status=running" in blob
    has_run_id = bool(re.search(r'"runId"\s*:\s*"[^"]+"', blob)) or "runId" in blob
    return has_running and has_run_id


def await_steps_completed(await_steps: list[dict]) -> bool:
    """await 时间线步通常只有 summary.after=await_tool_run完成，不含 JSON 正文。"""
    if not await_steps:
        return False
    return any(str(s.get("lifecycle") or "") == "done" for s in await_steps)


def content_implies_await_done(content: str) -> bool:
    """模型可见 await JSON；正文常复述 status=done / 完成。"""
    if not content:
        return False
    low = content.lower()
    if re.search(r'"status"\s*:\s*"done"', content):
        return True
    if "status=done" in low or "`done`" in low:
        return True
    if "status" in low and "done" in low:
        return True
    return False


def run_exec(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S-EXEC] query={query[:120]}...")
    coll = chat_sse_live(token, conv_id, query)
    assistant = wait_assistant(token, conv_id)
    steps = merge_steps(coll.steps, assistant)
    step_ids = [str(s.get("id")) for s in steps]
    exec_steps = collect_tool_steps(steps, "sandbox__exec")
    await_steps = collect_tool_steps(steps, "await_tool_run")
    content = str(assistant.get("content") or "")
    all_blob = flatten_blobs(steps) + "\n" + content
    # exec 同步回执会落在 tool 步 result：{"ok":true,"runId":"...","status":"running"}
    running_ack = any(blob_has_running_ack(step_blob(s)) for s in exec_steps)
    await_lc_done = await_steps_completed(await_steps)
    await_done_signal = content_implies_await_done(content) or (
        await_lc_done and ("async-exec-ok" in content or "async-exec-ok" in all_blob)
    )
    marker_ok = "async-exec-ok" in content or "async-exec-ok" in all_blob
    hard_ok = (
        len(exec_steps) >= 1
        and len(await_steps) >= 1
        and running_ack
        and await_lc_done
        and await_done_signal
        and marker_ok
    )
    print(
        f"  exec_steps={len(exec_steps)} await_steps={len(await_steps)} "
        f"running_ack={running_ack} await_lc_done={await_lc_done} "
        f"await_done_signal={await_done_signal} marker={marker_ok}"
    )
    print(f"  steps={step_ids}")
    if not hard_ok:
        print(
            "  hint: 需 sandbox__exec(background=true) result 含 running+runId，"
            "await_tool_run 步 lifecycle=done，且正文/标记证明终态拿到 async-exec-ok"
        )
        for s in exec_steps[:2]:
            print(f"  exec_blob={step_blob(s)[:240]!r}")
        for s in await_steps[:2]:
            print(f"  await_summary={s.get('summary')!r} lifecycle={s.get('lifecycle')}")
        print(f"  content_preview={content[:200]!r}")
    return {
        "pass": hard_ok,
        "exec_count": len(exec_steps),
        "await_count": len(await_steps),
        "running_ack": running_ack,
        "await_lc_done": await_lc_done,
        "await_done_signal": await_done_signal,
        "marker_ok": marker_ok,
        "step_ids": step_ids,
        "content_preview": content[:200],
        "message_status": coll.message_status or assistant.get("status"),
    }


def run_spawn(token: str, conv_id: str, query: str) -> dict:
    print(f"\n[S-SPAWN] query={query[:120]}...")
    coll = chat_sse_live(token, conv_id, query)
    assistant = wait_assistant(token, conv_id)
    steps = merge_steps(coll.steps, assistant)
    step_ids = [str(s.get("id")) for s in steps]
    sub_cards = collect_subagent_steps(steps)
    await_steps = collect_tool_steps(steps, "await_tool_run")
    content = str(assistant.get("content") or "")
    all_blob = flatten_blobs(steps) + "\n" + content
    # spawn 不上 tool-*；以子卡 + await 步 + 正文终态信号为准
    await_lc_done = await_steps_completed(await_steps)
    await_done_signal = content_implies_await_done(content) or (
        await_lc_done and ("async-spawn-ok" in content or "async-spawn-ok" in all_blob)
    )
    marker_ok = "async-spawn-ok" in content or "async-spawn-ok" in all_blob
    hard_ok = (
        len(sub_cards) >= 1
        and len(await_steps) >= 1
        and await_lc_done
        and await_done_signal
        and marker_ok
    )
    print(
        f"  subagent={len(sub_cards)} await_steps={len(await_steps)} "
        f"await_lc_done={await_lc_done} await_done_signal={await_done_signal} "
        f"marker={marker_ok}"
    )
    print(f"  steps={step_ids}")
    print(f"  subagent_ids={[str(s.get('id')) for s in sub_cards]}")
    if not hard_ok:
        print(
            "  hint: 需 subagent-* 卡 + await_tool_run(lifecycle=done) + 正文含 async-spawn-ok；"
            "确认 spawn background=true 与 async-tool.enabled"
        )
        for s in await_steps[:2]:
            print(f"  await_summary={s.get('summary')!r} lifecycle={s.get('lifecycle')}")
        print(f"  content_preview={content[:200]!r}")
    return {
        "pass": hard_ok,
        "subagent_count": len(sub_cards),
        "await_count": len(await_steps),
        "await_lc_done": await_lc_done,
        "await_done_signal": await_done_signal,
        "marker_ok": marker_ok,
        "step_ids": step_ids,
        "content_preview": content[:200],
        "message_status": coll.message_status or assistant.get("status"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="async-tool await Live verifier")
    parser.add_argument(
        "--suite",
        default="exec,spawn",
        help="comma list: exec,spawn (default both)",
    )
    parser.add_argument("--print-prompts", action="store_true")
    args = parser.parse_args()
    suites = {x.strip().lower() for x in args.suite.split(",") if x.strip()}
    if args.print_prompts:
        print("=== EXEC ===\n", EXEC_QUERY)
        print("\n=== SPAWN ===\n", SPAWN_QUERY)
        return 0

    print(f"=== async-tool await Live ===\nGateway={GATEWAY_URL} timeout={TIMEOUT_SEC}s")
    print(f"suites={sorted(suites)}")
    try:
        token, conv0 = setup_auth()
    except Exception as e:
        print(f"SETUP FAIL: {e}")
        return 2

    results: dict[str, dict] = {}
    if "exec" in suites:
        try:
            results["exec"] = run_exec(token, conv0, EXEC_QUERY)
        except Exception as e:
            print(f"S-EXEC ERROR: {e}")
            results["exec"] = {"pass": False, "error": str(e)}
    if "spawn" in suites:
        try:
            conv_s = new_conversation(token)
            results["spawn"] = run_spawn(token, conv_s, SPAWN_QUERY)
        except Exception as e:
            print(f"S-SPAWN ERROR: {e}")
            results["spawn"] = {"pass": False, "error": str(e)}

    print("\n=== SUMMARY ===")
    all_pass = True
    for name, r in results.items():
        ok = bool(r.get("pass"))
        all_pass = all_pass and ok
        print(f"  S-{name.upper()}: {'PASS' if ok else 'FAIL'} {json.dumps(r, ensure_ascii=False)[:300]}")
    print("OVERALL:", "PASS" if all_pass else "FAIL")
    return 0 if all_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
