#!/usr/bin/env python3
"""M3 session_search 收缩版 Live 验收 — task-list-memory §9 M3 / plan 2026-08-24 M3。

覆盖场景（plan §4 验收）:
  P1   task 会话（fast）落一条含约定标记的消息 → chat_message 落库 + L3 ingest（等待）
  P2   同会话再发消息，命令模型调用 sunshine_session_search 恢复早前约定标记
  P3   证据断言：orchestrator 日志新增 `[SessionSearchTool] 本会话正文检索`（硬）+
       P2 回复含约定标记（软）；工具集注册日志含 sunshine_session_search
  P4   chat 会话对照：同款引导消息 → 日志无新增 SessionSearchTool（chat 不注册工具，隔离成立）

断言策略与 M1/M2 脚本一致：链路证据（日志/注册）为主、模型回复行为证据为辅。
工具调用是模型自主行为，P2 以「日志出现 SessionSearchTool 检索 + 回复恢复标记」双证据判定。

用法:
  python3 scripts/verify_session_search_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  TIMEOUT_SEC（单轮 SSE 上限，默认 300）
  ORCH_LOG（orchestrator 日志路径，默认 ../logs/sunshine-orchestrator.log）
"""
from __future__ import annotations

import json
import os
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

# P1 落一个唯一约定标记，P2 命令模型经 sunshine_session_search 恢复
P1_MARK_PREFIX = "SUNSHINE-SESSION-SEARCH-0817"
P2_QUERY = (
    "请调用工具 sunshine_session_search 检索本次会话的历史正文，"
    "找出我在本会话开始时提到的约定标记（以 SUNSHINE-SESSION-SEARCH- 开头的字符串），"
    "并原样告诉我。不要凭记忆猜测，必须基于工具返回结果；若工具未返回该标记请如实说明。"
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
    user = f"ssrch_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "SessionSearch"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    data = login.get("data") or {}
    token = data.get("token")
    user_id = data.get("userId") or ""
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    body: dict = {"kind": kind}
    if workspace_id:
        body["workspaceId"] = workspace_id
    conv = auth_json("POST", "/api/conversations", body, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, str(conv_id), str(user_id)


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
    """chat_message 中该会话正文含标记（user 或 assistant 消息）。"""
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
    """探测 L3 ingest：rag-service chat-history list 返回该会话 chunk >= min_chunks。"""
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


def run_p1(fails: list[str]) -> tuple[str, str, str, str, int]:
    """P1：task 会话落一条含唯一约定标记的消息。返回 (token, conv, mark, user_id, log_offset)。"""
    print("\n=== P1 task 会话落约定标记消息（L3 body ingest 数据源）===")
    ws_id = f"ws-ssrch-{uuid.uuid4().hex[:8]}"
    token, conv, user_id = setup_auth(kind="task", workspace_id=ws_id)
    mark = f"{P1_MARK_PREFIX}-{uuid.uuid4().hex[:6].upper()}"
    p1 = f"请记住本会话约定标记：{mark}。只需确认记住即可，不需要执行其他任务。"
    run_fast(token, conv, p1, label="P1")
    if not wait_for(lambda: conv_has_mark(conv, mark), timeout=120, desc="P1 消息落库"):
        fail(f"P1 约定标记未落库 conv={conv}")
        fails.append("P1-mark-not-persisted")
        return token, conv, mark, user_id, log_line_count()
    ok(f"P1 约定标记落库 conv={conv}")
    # L3 ingest 为 completed 后异步执行，轮询 rag-service list 直到正文写入 Milvus（供 P2 检索）
    if not wait_for(lambda: conv_ingested(user_id, conv, min_chunks=2), timeout=120, desc="L3 ingest 完成"):
        warn("P1 L3 ingest 超时未完成（可能影响 P2 检索命中）")
    else:
        ok("P1 正文已 ingest 进 Milvus（session_search 数据源就绪）")
    return token, conv, mark, user_id, log_line_count()


def run_p2_p3(fails: list[str], token: str, conv: str, mark: str, before_offset: int) -> None:
    """P2+P3：命令模型调用 sunshine_session_search 恢复标记，验证调用链路 + 注册 + 恢复。"""
    print("\n=== P2 同会话命令模型调用 sunshine_session_search 恢复标记 ===")
    text = run_fast(token, conv, P2_QUERY, label="P2")
    recovered = mark in text
    print(f"  P2 是否恢复标记 {mark}: {'是' if recovered else '否'}")
    if not recovered:
        warn("P2 回复未含约定标记（模型行为软证据；以链路日志为准）")
        fails.append("P2-mark-not-recovered")

    print("\n=== P3 链路证据：SessionSearchTool 检索日志 + 工具集注册 ===")
    new_lines = log_new_lines(before_offset)
    search_logged = any("[SessionSearchTool] 本会话正文检索" in ln for ln in new_lines)
    registered = any("sunshine_session_search" in ln for ln in log_new_lines(0))
    if search_logged:
        ok("P3 orchestrator 日志出现 [SessionSearchTool] 本会话正文检索（工具被调用）")
    else:
        fail("P3 未见 [SessionSearchTool] 检索日志（sunshine_session_search 未被模型调用）")
        fails.append("P3-session-search-not-invoked")
    if registered:
        ok("P3 工具集注册日志含 sunshine_session_search（task 会话已注入）")
    else:
        warn("P3 当前日志未见 sunshine_session_search 注册行（可能已在更早日志）")
    if not recovered and search_logged:
        # 工具调用了但模型没复述 → 检索返回与标记不匹配（可能检索 topK/评分问题）
        fail("P3 工具已调用但标记未恢复（检索未命中？）")
        fails.append("P3-search-miss")
    if recovered and search_logged:
        ok("P3 端到端闭环：session_search 检索 → 模型恢复约定标记")


def run_p4(fails: list[str], before_offset: int) -> None:
    """P4：chat 会话对照——同款引导不应触发 SessionSearchTool（chat 不注册）。"""
    print("\n=== P4 chat 会话对照：chat 不注册 sunshine_session_search（隔离）===")
    token, conv, _ = setup_auth(kind="chat")
    run_fast(token, conv, P2_QUERY, label="P4")
    new_lines = log_new_lines(before_offset)
    search_logged = any("[SessionSearchTool] 本会话正文检索" in ln for ln in new_lines)
    if search_logged:
        fail("P4 chat 会话出现了 SessionSearchTool 检索（chat 不应注册该工具）")
        fails.append("P4-chat-session-search-leaked")
    else:
        ok("P4 chat 会话未触发 SessionSearchTool（工具仅 task 会话注册）")


def main() -> None:
    print("== M3 session_search 收缩版（body + scope=session）Live 验收 ==")
    preflight_gateway()
    fails: list[str] = []
    token, conv, mark, user_id, before = run_p1(fails)
    run_p2_p3(fails, token, conv, mark, before)
    run_p4(fails, log_line_count())

    print("\n==== 结果 ====")
    if fails:
        print(f"  ❌ 失败项: {fails}", file=sys.stderr)
        sys.exit(1)
    print("  ✅ ALL PASSED")


if __name__ == "__main__":
    main()
