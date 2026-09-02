#!/usr/bin/env python3
"""ReAct usage 链路 live 验收（轮次 / 输入输出 token / ctx 分组）。

| # | 断言 | 期望 |
|---|------|------|
| 1 | type=usage 帧数与 callSeq | ≥2 且单调递增 |
| 2 | 末帧 messageUsage.llmCalls | == usage 帧数 |
| 3 | 末帧 contextWindowTokens / contextPercent | 存在且 ≈ round(100*contextTokens/window) |
| 4 | 落库 GET 会话详情末条 assistant.usage | 非空且 llmCalls 与 SSE 一致 |
| 5 | 末帧 groups.system | 存在且 > 0 |

用法:
  python3 scripts/verify_usage_stream_live.py
  GATEWAY_URL=http://ecs4c16g:8000 python3 scripts/verify_usage_stream_live.py

前置:
  1. 线上 MySQL 已执行:
     ALTER TABLE sunshine_chat.chat_message
       ADD COLUMN usage_json MEDIUMTEXT NULL COMMENT '消息级 LLM usage + 上下文分组快照 JSON'
       AFTER content_blocks;
  2. llm-gateway / orchestrator 已用新代码打包重启:
     python scripts/start.py --restart llm-gateway orchestrator
  3. Gateway / auth / LLM 可用。

说明:
  走 executionMode=fast（ReAct 主链路；usage 采集仅接 ReActAgentRuntime）。
  查询强制 RAG 检索，稳定产生多次模型调用（think → tool → think-2 → generate）。

环境变量:
  GATEWAY_URL, USAGE_LIVE_TIMEOUT_SEC
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("USAGE_LIVE_TIMEOUT_SEC", "180"))
# plan 原设「分两步」查询实际只触发 1 次模型调用（无工具直答）；
# 改为强制 RAG 检索的查询，稳定产生多次模型调用（think → tool → think-2 → generate）
QUERY = "请先查询知识库：青松假有多少天、怎么申请？查到后给我一个简短总结。"


def ok_line(msg: str) -> None:
    print(f"  ✅ {msg}")


def fail_line(msg: str, *, hint: str | None = None) -> None:
    print(f"  ❌ {msg}", file=sys.stderr)
    if hint:
        print(f"     → {hint}", file=sys.stderr)


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def preflight_gateway() -> None:
    try:
        requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
    except requests.RequestException as exc:
        raise RuntimeError(
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). 请先启动全链路: python scripts/start.py"
        ) from exc


def setup_auth() -> str:
    user = f"usage_live_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "UsageLive"},
        None,
    )
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    return token


def new_conversation(token: str) -> str:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return str(conv_id)


def chat_sse(token: str, conv_id: str) -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body = {"content": QUERY, "conversationId": conv_id, "executionMode": "fast"}
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
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        raw = (proc.stdout or "") + (proc.stderr or "")
        if "__HTTP_CODE__" in raw:
            raw = raw.rpartition("__HTTP_CODE__")[0]
        return raw
    finally:
        os.unlink(tmp)


def parse_usage_frames(raw: str) -> list[dict]:
    frames: list[dict] = []
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
        if isinstance(obj, dict) and obj.get("type") == "usage":
            frames.append(obj)
    return frames


def wait_assistant(token: str, conv_id: str, max_wait: int = 90) -> dict | None:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        data = detail.get("data") or detail
        messages = data.get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") in ("completed", "interrupted", "failed"):
            return assistants[-1]
        time.sleep(2)
    return None


def main() -> int:
    print("=== ReAct usage stream live verify ===")
    print(f"Gateway={GATEWAY_URL} timeout={TIMEOUT_SEC}s mode=fast")

    try:
        preflight_gateway()
        token = setup_auth()
    except Exception as exc:
        print(f"\n❌ FAIL: {exc}", file=sys.stderr)
        return 1

    conv_id = new_conversation(token)
    print(f"conv={conv_id} query={QUERY}")
    raw = chat_sse(token, conv_id)
    frames = parse_usage_frames(raw)

    failed = False

    # 断言 1：帧数 ≥2 且 callSeq 单调递增
    seqs = [f.get("callSeq") for f in frames]
    if len(frames) >= 2 and all(isinstance(s, int) for s in seqs) and seqs == sorted(seqs) and len(set(seqs)) == len(seqs):
        ok_line(f"usage 帧数={len(frames)}，callSeq={seqs} 单调递增")
    else:
        failed = True
        fail_line(
            f"usage 帧异常：count={len(frames)} seqs={seqs}",
            hint="确认 llm-gateway 透传 stream_options 且 orchestrator 已重启加载新代码",
        )
        print("\n❌ FAIL: usage 帧缺失，后续断言跳过", file=sys.stderr)
        return 1

    last = frames[-1]

    # 断言 2：末帧 messageUsage.llmCalls == 帧数
    llm_calls = ((last.get("messageUsage") or {}).get("llmCalls"))
    if llm_calls == len(frames):
        ok_line(f"末帧 messageUsage.llmCalls={llm_calls} == 帧数")
    else:
        failed = True
        fail_line(f"末帧 llmCalls={llm_calls} != 帧数 {len(frames)}")

    # 断言 3：contextWindowTokens 存在且 contextPercent ≈ round(100*contextTokens/window)
    window = last.get("contextWindowTokens")
    percent = last.get("contextPercent")
    ctx_tokens = last.get("contextTokens")
    if isinstance(window, int) and window > 0 and isinstance(ctx_tokens, int):
        expect = round(100 * ctx_tokens / window)
        if percent == expect:
            ok_line(f"contextPercent={percent} ≈ round(100*{ctx_tokens}/{window})")
        else:
            failed = True
            fail_line(f"contextPercent={percent} 期望 {expect}（ctx={ctx_tokens} window={window}）")
    else:
        failed = True
        fail_line(
            f"末帧缺 window/ctx：window={window!r} ctx={ctx_tokens!r}",
            hint="检查 model-registry context_window 配置与 ModelWindowCache",
        )

    # 断言 4：落库 usage 非空且 llmCalls 一致
    assistant = wait_assistant(token, conv_id)
    persisted_usage = assistant.get("usage") if assistant else None
    if persisted_usage:
        try:
            pj = json.loads(persisted_usage) if isinstance(persisted_usage, str) else persisted_usage
            persisted_calls = ((pj.get("messageUsage") or {}).get("llmCalls"))
            if persisted_calls == len(frames):
                ok_line(f"落库 usage 非空且 llmCalls={persisted_calls} 与 SSE 一致")
            else:
                failed = True
                fail_line(f"落库 llmCalls={persisted_calls} != SSE 帧数 {len(frames)}")
        except (json.JSONDecodeError, AttributeError) as exc:
            failed = True
            fail_line(f"落库 usage 解析失败: {exc}")
    else:
        failed = True
        fail_line(
            "落库 usage 为空",
            hint="确认 ALTER TABLE chat_message ADD usage_json 已执行且 orchestrator 已重启",
        )

    # 断言 5：groups.system > 0
    groups = last.get("groups") or {}
    if isinstance(groups.get("system"), int) and groups["system"] > 0:
        ok_line(f"groups.system={groups['system']} > 0")
    else:
        failed = True
        fail_line(f"groups.system 异常: {groups.get('system')!r}")

    if failed:
        print("\n❌ FAIL", file=sys.stderr)
        return 1
    print("\n✅ PASS all usage-stream assertions")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n中断", file=sys.stderr)
        raise SystemExit(130)
