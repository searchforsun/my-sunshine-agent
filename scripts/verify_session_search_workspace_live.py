#!/usr/bin/env python3
"""M3 扩展 workspace Live 验收 — task-list-memory §9 M3 扩展（scope=workspace 跨会话正文）。

覆盖场景:
  W1   建同工作区 A/B 两个 task 会话（workspaceId 相同）
  W2   A 会话落唯一约定标记（第 2 条触发 L3 flush）→ chat_message 落库 + L3 body ingest 进 Milvus
  W3   B 会话命令模型调用 sunshine_session_search（scope=workspace）检索标记
  W4   断言：orchestrator 日志出现 `[SessionSearchTool] 工作区跨会话检索 ... convs=1`
       （硬：排除当前会话后剩 1 个）+ B 回复含标记（软）+ 工具注册含 sunshine_session_search

断言策略与 verify_session_search_live.py 一致：链路证据（日志/注册）为主、模型行为证据为辅。
convs=N 断言是 workspace 扩展的专用硬证据——工作区恰 2 个 task 会话时排除当前会话后必须为 1。

用法:
  python3 scripts/verify_session_search_workspace_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  TIMEOUT_SEC（单轮 SSE 上限，默认 300）
  ORCH_LOG（orchestrator 日志路径，默认 ../logs/sunshine-orchestrator.log）
"""
from __future__ import annotations

import json
import os
import re
import shutil
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

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
RAG_URL = os.environ.get("RAG_URL", "http://127.0.0.1:8400").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("TIMEOUT_SEC", "300"))
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}
LOG_PATH = os.environ.get(
    "ORCH_LOG", os.path.join(os.path.dirname(__file__), "..", "logs", "sunshine-orchestrator.log"))

MARK_PREFIX = "WSSEARCH-0817"
WS_QUERY = (
    "请调用工具 sunshine_session_search，将 scope 参数设置为 workspace，"
    "检索当前工作区其他任务会话中关于约定标记（以 WSSEARCH- 开头的字符串）的正文，"
    "找到后把标记原样告诉我。不要凭记忆猜测，必须基于工具返回结果；若未找到请如实说明。"
)


def fail(msg: str) -> None:
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def warn(msg: str) -> None:
    print(f"  ⚠ {msg}")


def sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def mysql_lines(sql: str) -> list[str]:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [mysql, "-h", MYSQL["host"], "-P", str(MYSQL["port"]),
         "-u", MYSQL["user"], f"-p{MYSQL['password']}",
         "sunshine_chat", "-N", "-B", "-e", sql],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")
    return [ln for ln in proc.stdout.splitlines() if ln.strip()]


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
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). 请先 python scripts/start.py"
        ) from exc


def setup_auth(*, kind: str, workspace_id: str | None = None) -> tuple[str, str, str]:
    """注册新用户并创建会话。返回 (token, conv_id, user_id)。"""
    token, user_id = register_login("WorkspaceSearch")
    return token, create_conv(token, kind, workspace_id), user_id


def register_login(nickname: str) -> tuple[str, str]:
    """注册 + 登录，返回 (token, user_id)。"""
    user = f"wssrch_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": nickname}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    data = login.get("data") or {}
    token = data.get("token")
    user_id = data.get("userId") or ""
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    return token, str(user_id)


def create_conv(token: str, kind: str, workspace_id: str | None = None) -> str:
    """同一用户创建会话（A/B 同用户是 workspace 检索的前提：findTaskIdsByWorkspace 按 userId 过滤）。"""
    body: dict = {"kind": kind}
    if workspace_id:
        body["workspaceId"] = workspace_id
    conv = auth_json("POST", "/api/conversations", body, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return str(conv_id)


def chat_sse(token: str, conv_id: str, query: str) -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    payload = json.dumps(
        {"content": query, "conversationId": conv_id, "executionMode": "fast"},
        ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [curl, "-N", "-s", "-m", str(TIMEOUT_SEC), "-X", "POST",
             f"{GATEWAY_URL}/api/chat/stream",
             "-H", f"Authorization: Bearer {token}",
             "-H", "Content-Type: application/json",
             "--data-binary", f"@{tmp}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE failed curl exit={proc.returncode}")
        return raw
    finally:
        os.unlink(tmp)


def sse_text(raw: str) -> str:
    parts: list[str] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            obj = json.loads(payload)
        except Exception:
            continue
        if obj.get("type") in ("content", "message", "delta"):
            text = obj.get("text") or obj.get("content")
            if isinstance(text, str) and text.strip():
                parts.append(text)
    return "".join(parts)


def conv_has_mark(conv_id: str, mark: str) -> bool:
    sql = (
        "SELECT COUNT(*) FROM chat_message "
        "WHERE conversation_id='{c}' AND (content LIKE '%{m}%' OR steps LIKE '%{m}%')"
    ).format(c=sql_escape(conv_id), m=sql_escape(mark))
    lines = mysql_lines(sql)
    return bool(lines) and lines[0].strip() != "0"


def log_new_lines(offset: int) -> list[str]:
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError as exc:
        warn(f"orchestrator 日志不可读（{exc}）")
        return []
    return lines[offset:]


def log_line_count() -> int:
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
            return len(f.read().splitlines())
    except OSError:
        return 0


def wait_for(cond, *, timeout: int, interval: float = 1.0, desc: str) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if cond():
                return True
        except RuntimeError:
            pass
        time.sleep(interval)
    return False


def run_fast(token: str, conv_id: str, query: str, *, label: str) -> str:
    print(f"  -- {label} 发 fast 消息: {query[:48]}{'…' if len(query) > 48 else ''}")
    raw = chat_sse(token, conv_id, query)
    text = sse_text(raw)
    print(f"  -- {label} 回复开头: {text[:70].replace(chr(10), ' ')}{'…' if len(text) > 70 else ''}")
    return text


def conv_ingested(user_id: str, conv_id: str, min_chunks: int = 2) -> bool:
    if not user_id or not conv_id:
        return False
    try:
        resp = requests.post(
            f"{RAG_URL}/api/rag/chat-history/list",
            json={"userId": user_id, "tenantId": "default", "convId": conv_id, "limit": 50},
            timeout=5)
        if resp.status_code != 200:
            return False
        data = resp.json()
        results = ((data.get("data") or {}).get("results")) or []
        return len(results) >= min_chunks
    except requests.RequestException:
        return False


def run_w1_w2(fails: list[str]) -> tuple[str, str, str, str, str, int]:
    """W1：同工作区建 A/B 两会话。W2：A 落标记（2 条消息触发 L3 flush）。"""
    print("\n=== W1 建同工作区 A/B 两个 task 会话 ===")
    ws_id = f"ws-wssrch-{uuid.uuid4().hex[:8]}"
    # A/B 必须同一用户：findTaskIdsByWorkspace 按 userId 过滤，跨用户会话不属于同一检索面
    token_a, user_a = register_login("WorkspaceSearch")
    conv_a = create_conv(token_a, "task", ws_id)
    conv_b = create_conv(token_a, "task", ws_id)
    ok(f"工作区 {ws_id} / 会话 A={conv_a} / 会话 B={conv_b}")

    print("\n=== W2 A 会话落约定标记（第 2 条触发 L3 flush）===")
    mark = f"{MARK_PREFIX}-{uuid.uuid4().hex[:6].upper()}"
    run_fast(token_a, conv_a, f"请记住本会话约定标记：{mark}。只需确认记住即可。", label="W2-A1")
    run_fast(token_a, conv_a,
             "补充：该标记是跨会话工作区检索的验收目标，其他任务会话需要能通过 scope=workspace 检索到它。",
             label="W2-A2")
    if not wait_for(lambda: conv_has_mark(conv_a, mark), timeout=120, desc="W2 A 标记落库"):
        fail(f"W2 约定标记未落库 conv={conv_a}")
        fails.append("W2-mark-not-persisted")
    else:
        ok(f"W2 A 约定标记落库 conv={conv_a}")
    if not wait_for(lambda: conv_ingested(user_a, conv_a, min_chunks=2), timeout=180, desc="W2 L3 body ingest"):
        warn("W2 A 正文 ingest 超时未完成（可能影响 W3 检索命中）")
    else:
        ok("W2 A 正文已 ingest 进 Milvus（workspace 检索数据源就绪）")
    return token_a, conv_a, conv_b, mark, user_a, log_line_count()


def run_w3_w4(fails: list[str], token: str, conv_b: str, mark: str, before_offset: int) -> None:
    """W3：B 会话命令模型 scope=workspace 检索。W4：断言日志 convs + 复述 + 注册。"""
    print("\n=== W3 B 会话命令模型 scope=workspace 检索标记 ===")
    text = run_fast(token, conv_b, WS_QUERY, label="W3-B")
    recovered = mark in text
    print(f"  W3 B 是否恢复标记 {mark}: {'是' if recovered else '否'}")
    if not recovered:
        warn("W3 B 回复未含约定标记（模型行为软证据；以链路日志为准）")
        fails.append("W3-mark-not-recovered")

    print("\n=== W4 链路证据：工作区跨会话检索日志 convs + 工具注册 ===")
    new_lines = log_new_lines(before_offset)
    ws_logs = [ln for ln in new_lines if "[SessionSearchTool] 工作区跨会话检索" in ln]
    if ws_logs:
        ok(f"W4 orchestrator 日志出现工作区跨会话检索（工具被调用，{len(ws_logs)} 行）")
        convs = None
        for ln in ws_logs:
            m = re.search(r"convs=(\d+)", ln)
            if m:
                convs = int(m.group(1))
                break
        if convs == 1:
            ok("W4 convs=1（工作区 2 个 task 会话，排除当前会话后剩 1 个）")
        else:
            fail(f"W4 convs={convs}，期望 1（排除当前会话逻辑异常）")
            fails.append("W4-convs-unexpected")
    else:
        fail("W4 未见工作区跨会话检索日志（scope=workspace 未被模型调用）")
        fails.append("W4-workspace-search-not-invoked")
    registered = any("sunshine_session_search" in ln for ln in log_new_lines(0))
    if registered:
        ok("W4 工具集注册日志含 sunshine_session_search（task 会话已注入）")
    else:
        warn("W4 当前日志未见 sunshine_session_search 注册行（可能已在更早日志）")
    if recovered and ws_logs:
        ok("W4 端到端闭环：scope=workspace 检索 → 模型复述跨会话标记")


def main() -> None:
    print("== M3 扩展 workspace（scope=workspace 跨会话正文）Live 验收 ==")
    preflight_gateway()
    fails: list[str] = []
    token, conv_a, conv_b, mark, user_a, before = run_w1_w2(fails)
    run_w3_w4(fails, token, conv_b, mark, before)

    print("\n==== 结果 ====")
    if fails:
        print(f"  ❌ 失败项: {fails}", file=sys.stderr)
        sys.exit(1)
    print("  ✅ ALL PASSED")


if __name__ == "__main__":
    main()
