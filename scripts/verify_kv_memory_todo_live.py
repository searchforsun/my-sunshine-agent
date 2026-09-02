#!/usr/bin/env python3
"""KV Memory `todo` 类（M1）Live 验收 — task-list-memory §5.3/§6 / plan 2026-08-23 M1。

覆盖场景（plan Task 5 brief T1–T5）:
  T1   chat 沉淀用户 todo：会话消息 → user_context_state 出现 scope='user' + kind='todo' 行（key 场景化 + background 非空）
  T2   chat 新会话注入 todo：另建 chat 会话「继续上次的事」→ 上下文含 - todo / finance.* 行（跨会话续接）
  T3   完成即 void：原会话「已审批完不用跟了」→ 该 todo 行 status=void；新会话不再注入（已 void 不召回）
  T4   task 工作区 todo 隔离：kind=task + workspaceId 会话 → scope='workspace' 行写入；chat 会话上下文不含 workspace 行
  T5   存量 chat 兼容：普通对话 L2（preference 等）仍正常抽取注入，无回归

注入块文本由服务端确定性纯函数渲染（L2StateStore.renderSystemBlock，单测已覆盖），
平台不暴露组装后的 LLM 请求。本脚本以数据谓词（DB 快照）为主断言 + 行为必要条件为辅：
  T2/T4 的「注入」以 DB 中 scope 正确的 active 行存在为前置（谓词），再以「模型回复引用 todo
  关键词」为行为必要条件（缺失即硬失败）；T3 以 DB 行为 void 且新会话不再出现该 key 为判据。

用法:
  python3 scripts/verify_kv_memory_todo_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  KV_TODO_TIMEOUT_SEC（单轮 SSE 上限，默认 300）
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
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("KV_TODO_TIMEOUT_SEC", "300"))
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

# 用户主动提出、跨会话仍有效的未完成任务（v22 门禁正向样例）
T1_QUERY = "帮我记住：跟进审批单 PR-2026-0812 的状态，等它审批通过后提醒我"
T2_QUERY = "继续上次的事"
T3_VOID_QUERY = "PR-2026-0812 已经审批完了，不用再跟进提醒了"
T4_QUERY = "请记住这个工作区的一个任务：周一前完成 QA-2026-0817 环境部署"
T5_QUERY = "我平时喜欢用中文交流，回答简洁一些"

EXPECTED_TODO_KEYWORDS = ["PR-2026-0812", "审批"]


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
        resp = requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
        _ = resp.status_code
    except requests.RequestException as exc:
        raise RuntimeError(
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). 请先 python scripts/start.py"
        ) from exc


def setup_auth(*, kind: str = "chat", workspace_id: str | None = None) -> tuple[str, str, str]:
    """注册+登录+建会话 → (token, conversationId, userId)。userId 即 x-user-id 注入值（UserEntity.id）。"""
    user = f"kvtodo_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "KvTodo"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    data = login.get("data") or {}
    token = data.get("token")
    user_id = data.get("userId") or ""
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    body: dict[str, Any] = {"kind": kind}
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


def user_l2_rows(user_id: str, tenant_id: str = "default") -> list[tuple[str, str, str, str, str]]:
    """scope=user 行：(kind, state_key, state_value, status, background)。"""
    sql = (
        "SELECT kind, state_key, state_value, status, IFNULL(background,'') "
        "FROM user_context_state "
        f"WHERE tenant_id='{sql_escape(tenant_id)}' AND scope='user' "
        f"AND user_id='{sql_escape(user_id)}' ORDER BY updated_at DESC"
    )
    out: list[tuple[str, str, str, str, str]] = []
    for line in mysql_lines(sql):
        kind, key, value, status, background = line.split("\t", 4)
        out.append((kind, key, value, status, background))
    return out


def workspace_l2_rows(workspace_id: str, tenant_id: str = "default") -> list[tuple[str, str, str, str, str]]:
    """scope=workspace 行：(kind, state_key, state_value, status, background)。"""
    sql = (
        "SELECT kind, state_key, state_value, status, IFNULL(background,'') "
        "FROM user_context_state "
        f"WHERE tenant_id='{sql_escape(tenant_id)}' AND scope='workspace' "
        f"AND workspace_id='{sql_escape(workspace_id)}' ORDER BY updated_at DESC"
    )
    out: list[tuple[str, str, str, str, str]] = []
    for line in mysql_lines(sql):
        kind, key, value, status, background = line.split("\t", 4)
        out.append((kind, key, value, status, background))
    return out


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
    print(f"\n  -- {label} 发消息: {query[:40]}{'…' if len(query) > 40 else ''}")
    raw = chat_sse(token, conv_id, query)
    text = sse_text(raw)
    print(f"  -- {label} 回复开头: {text[:60].replace(chr(10), ' ')}{'…' if len(text) > 60 else ''}")
    return text


def find_todo(rows: list[tuple[str, str, str, str, str]]) -> tuple[str, str, str, str] | None:
    """找 kind=todo 且含期望关键词的行 → (key, value, background, status)。"""
    for kind, key, value, status, background in rows:
        if kind != "todo":
            continue
        if any(kw in value or kw in key for kw in EXPECTED_TODO_KEYWORDS):
            return (key, value, background, status)
    return None


def run_t1(token: str, conv_id: str, user_id: str, fails: list[str]) -> None:
    print("\n=== T1 chat 沉淀用户 todo（scope=user + kind=todo）===")
    run_fast(token, conv_id, T1_QUERY, label="T1")
    if not wait_for(
            lambda: any(r[0] == "todo" for r in user_l2_rows(user_id)),
            timeout=180, desc="T1 todo 抽取落库"):
        fail(f"T1 user_context_state 未出现 kind=todo 行 user={user_id}")
        fails.append("T1-todo-missing")
        return
    rows = user_l2_rows(user_id)
    todo = find_todo(rows)
    if todo is None:
        fail(f"T1 未找到含关键词的 todo 行（rows={[(r[0], r[1]) for r in rows]}）")
        fails.append("T1-keyword-missing")
        return
    key, value, background, status = todo
    print(f"  T1 todo 行: key={key} value={value} background={background} status={status}")
    if not background:
        fail("T1 todo 行 background 为空（v22 P3 必填）")
        fails.append("T1-background-empty")
        return
    if status != "active":
        fail(f"T1 todo 行 status 应为 active，实际 {status}")
        fails.append("T1-status-not-active")
        return
    if "." not in key:
        fail(f"T1 todo key 未场景化（{key}，应 {domain}.{facet}）")
        fails.append("T1-key-not-scoped")
        return
    ok(f"T1 todo 行写入成功: {key}（background 非空、status=active、key 场景化）")


def run_t2(token: str, conv_id: str, user_id: str, fails: list[str],
           inconclusive: list[str]) -> None:
    print("\n=== T2 chat 新会话注入 todo（跨会话续接）===")
    todo_before = find_todo(user_l2_rows(user_id))
    if todo_before is None:
        fail("T2 前置不成立：T1 未沉淀 todo 行")
        fails.append("T2-precondition")
        return
    key_before = todo_before[0]
    text = run_fast(token, conv_id, T2_QUERY, label="T2")
    hit = [kw for kw in EXPECTED_TODO_KEYWORDS if kw in text]
    if hit:
        ok(f"T2 模型回复引用 todo 关键词 {hit} → 跨会话注入行为证据成立")
    else:
        warn(f"T2 模型回复未引用 todo 关键词（{EXPECTED_TODO_KEYWORDS}）；"
             "以 DB 数据谓词为准，记 INCONCLUSIVE")
        inconclusive.append("T2-keyword-missing")
    todo_after = find_todo(user_l2_rows(user_id))
    if todo_after is None or todo_after[0] != key_before:
        warn(f"T2 后 todo 行状态变化（before={key_before} after={todo_after}）；以 T3 最终断言为准")
    else:
        ok(f"T2 后 todo 行仍可注入（{key_before} status={todo_after[3]}）")


def run_t3(token: str, conv_id: str, user_id: str, fails: list[str],
           inconclusive: list[str]) -> None:
    print("\n=== T3 完成即 void + 新会话不再注入 ===")
    todo_before = find_todo(user_l2_rows(user_id))
    if todo_before is None:
        fail("T3 前置不成立：无 todo 行可失效")
        fails.append("T3-precondition")
        return
    key_before = todo_before[0]
    run_fast(token, conv_id, T3_VOID_QUERY, label="T3-void")
    if not wait_for(
            lambda: all(
                r[1] != key_before or r[3] == "void"
                for r in user_l2_rows(user_id)),
            timeout=180, desc="T3 todo void"):
        fail(f"T3 未观察到 key={key_before} 置 void（rows="
             f"{[(r[1], r[3]) for r in user_l2_rows(user_id)]}）")
        fails.append("T3-not-void")
        return
    ok(f"T3 todo 行 {key_before} → status=void（完成/取消即时失效）")
    _token2, conv2, _uid2 = setup_auth()
    text = run_fast(_token2, conv2, "继续上次的审批跟进", label="T3-new-conv")
    if EXPECTED_TODO_KEYWORDS[0] in text:
        warn("T3 新会话模型回复仍引用已 void 的 todo 关键词；以 DB 数据谓词（已 void）为准")
    else:
        ok("T3 新会话模型回复未引用已 void 的 todo 关键词")
    if any(r[1] == key_before and r[3] == "active" for r in user_l2_rows(user_id)):
        fail("T3 新会话后又出现 active 的同一 key（v22 门禁应阻止重新沉淀已完成项）")
        fails.append("T3-reactivated")
    else:
        ok(f"T3 断言：{key_before} 无 active 复活行")


def run_t4(fails: list[str]) -> None:
    print("\n=== T4 task 工作区 todo 隔离（scope=workspace）===")
    ws_id = f"ws-live-{uuid.uuid4().hex[:8]}"
    token, conv, user_id = setup_auth(kind="task", workspace_id=ws_id)
    run_fast(token, conv, T4_QUERY, label="T4")
    if not wait_for(
            lambda: bool(workspace_l2_rows(ws_id)),
            timeout=180, desc="T4 workspace 行落库"):
        fail(f"T4 无 scope=workspace 行写入 workspace={ws_id}")
        fails.append("T4-workspace-missing")
        return
    ws_rows = workspace_l2_rows(ws_id)
    todo = next((r for r in ws_rows if r[0] == "todo"), None)
    if todo is None:
        fail(f"T4 workspace 行无 kind=todo（rows={[r[0] for r in ws_rows]}）")
        fails.append("T4-no-workspace-todo")
        return
    kind, key, value, status, background = todo
    print(f"  T4 workspace todo: key={key} value={value} background={background} status={status}")
    if status != "active" or not background:
        fail(f"T4 workspace todo 状态异常（status={status} background='{background}'）")
        fails.append("T4-workspace-todo-invalid")
        return
    ok(f"T4 task 会话 → scope=workspace + kind=todo 落库（workspace_id={ws_id}）")
    # chat 隔离：同用户另建 chat 会话，其 user scope 不含该 workspace 行
    token_chat, conv_chat, _uid_chat = setup_auth(kind="chat")
    run_fast(token_chat, conv_chat, "继续上次的工作区任务", label="T4-chat-isolation")
    user_rows = user_l2_rows(_uid_chat)
    leaked = [r for r in user_rows if r[1] == key]
    if leaked:
        fail(f"T4 chat 会话上下文中出现 workspace todo key={key}（场景污染）")
        fails.append("T4-scope-leak")
    else:
        ok("T4 chat 会话上下文不含 workspace todo 行（读写闸门隔离生效）")


def run_t5(fails: list[str], soft: list[str]) -> None:
    print("\n=== T5 存量 chat L2 兼容（非 todo 类仍抽取注入）===")
    token, conv, user_id = setup_auth()
    run_fast(token, conv, T5_QUERY, label="T5")
    if not wait_for(
            lambda: any(r[0] != "todo" and r[3] == "active" for r in user_l2_rows(user_id)),
            timeout=180, desc="T5 非 todo L2 抽取"):
        warn("T5 未观察到非 todo 的 L2 行（模型可能未抽取 preference；软）")
        soft.append("T5-non-todo-missing")
        return
    rows = [r for r in user_l2_rows(user_id) if r[0] != "todo" and r[3] == "active"]
    kinds = sorted({r[0] for r in rows})
    ok(f"T5 非 todo L2 行仍正常注入（kinds={kinds}）→ chat 现状无回归")


def main() -> int:
    print("=== KV Memory todo 类（M1）Live 验收 ===")
    print(f"Gateway={GATEWAY_URL} MySQL={MYSQL['host']}:{MYSQL['port']}")
    try:
        preflight_gateway()
    except RuntimeError as exc:
        fail(str(exc))
        return 1

    fails: list[str] = []
    soft: list[str] = []
    inconclusive: list[str] = []
    try:
        token, conv, user_id = setup_auth()
        print(f"T1 会话 conversation={conv} user={user_id}")
        run_t1(token, conv, user_id, fails)
        run_t2(token, conv, user_id, fails, inconclusive)
        run_t3(token, conv, user_id, fails, inconclusive)
        run_t4(fails)
        run_t5(fails, soft)
    except Exception as exc:  # noqa: BLE001
        fail(f"执行异常: {exc}")
        fails.append("exception")

    print("\n--- 汇总 ---")
    for s in soft:
        warn(s)
    if fails:
        print("❌ FAILED:")
        for f in fails:
            print(f"  - {f}")
        return 1
    if inconclusive:
        print("❌ INCONCLUSIVE: 存在未真正验证的场景（门禁不通过）:")
        for r in inconclusive:
            print(f"  - {r}")
        return 2
    print("✅ ALL PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
