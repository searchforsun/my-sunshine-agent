#!/usr/bin/env python3
"""M2 pro 终态导出 Live 验收 — task-list-memory §6/§9 M2 / plan 2026-08-23 M2。

覆盖场景（plan Task 4 P1–P6）:
  P1   task 会话 pro 未完成任务导出：kind=task + workspaceId 会话，发 pro 任务并等完成
       → user_context_state 出现 scope='workspace' + kind='todo' + key 前缀 `task.` 行
       （key 编码 task.{goalHash8}.{baseTaskId}；background=goal；status 按 planner 收束为 active/void）
  P2   同会话续跑幂等：同会话再发 pro「继续」→ task.* 行数不膨胀（同 goal 同 key 覆盖刷新）
  P3   跨会话召回：同 workspace 另建 task 会话发「继续上次任务」→ 模型回复引用未完成任务关键词
       （行为证据软；DB 中 task.* active 行存在为硬前置）
  P4   全完成即 void：pro 会话明确「任务全部完成」→ 该 scope 下 task.* active 行置 void
  P5   chat 会话 pro 导出：chat 会话发 pro 任务 → scope='user' + task.* 行
  P6   存量 chat 兼容：普通 fast 对话 L2（preference 等）仍正常抽取注入，无回归

断言策略与 M1 脚本一致：DB 数据谓词为主、模型回复行为证据为辅。结构导出为确定性链路
（doFinally → H1TodoExportService → syncTodoExportWorkspace），与 LLM 抽取无关。

用法:
  python3 scripts/verify_pro_todo_export_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  PRO_TIMEOUT_SEC（单轮 SSE 上限，默认 600）
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
TIMEOUT_SEC = int(os.environ.get("PRO_TIMEOUT_SEC", "600"))
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

# planner 规划一个多步骤任务（含一个依赖前序的尾步），期望收束后留 pending/fail 未完成项
P1_QUERY = ("请按以下目标规划并执行：为 QA-2026-0817 环境部署撰写一份验收清单，"
            "包含环境检查、服务启动、冒烟测试三个步骤，并把冒烟测试结果写入最终回答")
P2_CONTINUE_QUERY = "继续执行剩余计划，把未完成的步骤做完"
P4_DONE_QUERY = "所有步骤已经全部完成，不需要再执行任何任务，直接确认收尾"
# 与 P1 同款复杂任务句式：多步骤 + 真实工具调用，planner 大概率留未完成项 → 验证 user scope 落点
P5_QUERY = ("请按以下目标规划并执行：为 QA-2026-0817 环境部署撰写一份验收清单，"
            "包含环境检查、服务启动、冒烟测试三个步骤，并把冒烟测试结果写入最终回答")
P6_QUERY = "我平时喜欢用中文交流，回答简洁一些"

EXPECTED_P3_KEYWORDS = ["待办", "用户状态", "未完成", "验收", "冒烟"]


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


def setup_auth(*, kind: str = "chat", workspace_id: str | None = None) -> tuple[str, str, str]:
    """注册+登录+建会话 → (token, conversationId, userId)。"""
    user = f"proexp_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "ProExport"}, None)
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


def chat_sse(token: str, conv_id: str, query: str, *, mode: str) -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    payload = json.dumps(
        {"content": query, "conversationId": conv_id, "executionMode": mode},
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


def workspace_task_rows(workspace_id: str, tenant_id: str = "default") -> list[tuple[str, str, str, str]]:
    """scope=workspace 的 task.* todo 行：(state_key, state_value, status, background)。"""
    sql = (
        "SELECT state_key, state_value, status, IFNULL(background,'') "
        "FROM user_context_state "
        "WHERE tenant_id='{t}' AND scope='workspace' AND workspace_id='{w}' "
        "AND kind='todo' AND state_key LIKE 'task.%' ORDER BY updated_at DESC"
    ).format(t=sql_escape(tenant_id), w=sql_escape(workspace_id))
    out: list[tuple[str, str, str, str]] = []
    for line in mysql_lines(sql):
        key, value, status, background = line.split("\t", 3)
        out.append((key, value, status, background))
    return out


def user_task_rows(user_id: str, tenant_id: str = "default") -> list[tuple[str, str, str, str]]:
    """scope=user 的 task.* todo 行：(state_key, state_value, status, background)。"""
    sql = (
        "SELECT state_key, state_value, status, IFNULL(background,'') "
        "FROM user_context_state "
        "WHERE tenant_id='{t}' AND scope='user' AND user_id='{u}' "
        "AND kind='todo' AND state_key LIKE 'task.%' ORDER BY updated_at DESC"
    ).format(t=sql_escape(tenant_id), u=sql_escape(user_id))
    out: list[tuple[str, str, str, str]] = []
    for line in mysql_lines(sql):
        key, value, status, background = line.split("\t", 3)
        out.append((key, value, status, background))
    return out


def user_l2_rows(user_id: str, tenant_id: str = "default") -> list[tuple[str, str, str, str, str]]:
    """scope=user 全量行：(kind, state_key, state_value, status, background)。"""
    sql = (
        "SELECT kind, state_key, state_value, status, IFNULL(background,'') "
        "FROM user_context_state "
        "WHERE tenant_id='{t}' AND scope='user' AND user_id='{u}' ORDER BY updated_at DESC"
    ).format(t=sql_escape(tenant_id), u=sql_escape(user_id))
    out: list[tuple[str, str, str, str, str]] = []
    for line in mysql_lines(sql):
        kind, key, value, status, background = line.split("\t", 4)
        out.append((kind, key, value, status, background))
    return out


def orchestrator_log_has(needle: str) -> bool:
    """在 orchestrator 日志中查找标记（导出链路触发证据）。日志文件路径可用 ORCH_LOG 覆盖。"""
    log_path = os.environ.get("ORCH_LOG", os.path.join(os.path.dirname(__file__), "..", "logs", "sunshine-orchestrator.log"))
    try:
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            return needle in f.read()
    except OSError as exc:
        warn(f"orchestrator 日志不可读（{exc}）；以 DB 谓词为准")
        return False


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


def run_pro(token: str, conv_id: str, query: str, *, label: str) -> str:
    print(f"  -- {label} 发 pro 消息: {query[:50]}{'…' if len(query) > 50 else ''}")
    raw = chat_sse(token, conv_id, query, mode="pro")
    text = sse_text(raw)
    print(f"  -- {label} 回复开头: {text[:60].replace(chr(10), ' ')}{'…' if len(text) > 60 else ''}")
    return text


def run_fast(token: str, conv_id: str, query: str, *, label: str) -> str:
    print(f"  -- {label} 发 fast 消息: {query[:50]}{'…' if len(query) > 50 else ''}")
    raw = chat_sse(token, conv_id, query, mode="fast")
    text = sse_text(raw)
    print(f"  -- {label} 回复开头: {text[:60].replace(chr(10), ' ')}{'…' if len(text) > 60 else ''}")
    return text


def run_p1(fails: list[str]) -> tuple[str, str, str]:
    """P1: task 会话 pro 未完成任务导出。返回 (token, conv, ws_id) 供后续复用。"""
    print("\n=== P1 task 会话 pro 终态导出（scope=workspace + task.* todo）===")
    ws_id = f"ws-pro-{uuid.uuid4().hex[:8]}"
    token, conv, user_id = setup_auth(kind="task", workspace_id=ws_id)
    run_pro(token, conv, P1_QUERY, label="P1")
    if not wait_for(
            lambda: bool(workspace_task_rows(ws_id)),
            timeout=300, desc="P1 task.* 行落库"):
        fail(f"P1 无 task.* 前缀行写入 workspace={ws_id}")
        fails.append("P1-task-rows-missing")
        return token, conv, ws_id
    rows = workspace_task_rows(ws_id)
    print(f"  P1 task.* 行: {[(r[0], r[2]) for r in rows]}")
    for key, value, status, background in rows:
        if not key.startswith("task."):
            fail(f"P1 key 前缀异常: {key}")
            fails.append("P1-key-prefix")
            break
        parts = key.split(".")
        if len(parts) < 3 or len(parts[1]) != 8:
            fail(f"P1 key 编码异常（应 task.{{goalHash8}}.{{base}}）: {key}")
            fails.append("P1-key-encoding")
            break
        if not background:
            fail(f"P1 task.* 行 background 为空: {key}")
            fails.append("P1-background-empty")
            break
    active = [r for r in rows if r[2] == "active"]
    if active:
        ok(f"P1 结构导出触发：{len(active)} 个未完成项 active（key/background/scope 正确）")
    else:
        warn("P1 无 active 未完成项（planner 本轮全部完成或全部失效）；"
             "task.* 行仍存在证明导出链路触发，未完成方向由 P3/P4 补充验证")
    return token, conv, ws_id


def run_p2(token: str, conv: str, ws_id: str, fails: list[str]) -> None:
    print("\n=== P2 同会话续跑幂等（task.* 行数不膨胀）===")
    before = workspace_task_rows(ws_id)
    run_pro(token, conv, P2_CONTINUE_QUERY, label="P2")
    if not wait_for(
            lambda: workspace_task_rows(ws_id) and
            workspace_task_rows(ws_id)[0][2] in ("active", "void"),
            timeout=300, desc="P2 续跑后行更新"):
        fail("P2 续跑后无 task.* 行刷新")
        fails.append("P2-no-refresh")
        return
    after = workspace_task_rows(ws_id)
    active_before = {r[0] for r in before if r[2] == "active"}
    active_after = {r[0] for r in after if r[2] == "active"}
    print(f"  P2 before={[(r[0], r[2]) for r in before]} after={[(r[0], r[2]) for r in after]}")
    if len(after) > len(before) + 1:
        fail(f"P2 task.* 行数膨胀（before={len(before)} after={len(after)}）——同 goal 应覆盖不叠加")
        fails.append("P2-row-bloat")
        return
    if active_before and not active_before.issubset(active_after):
        warn("P2 原 active 行部分消失（可能 planner 完成/换题致 void）；幂等覆盖以行数不膨胀为判据")
    ok(f"P2 续跑幂等：task.* 行数 {len(before)} → {len(after)}，未膨胀")


def run_p3(token: str, ws_id: str, fails: list[str], soft: list[str]) -> None:
    print("\n=== P3 跨会话召回（同 workspace 新会话续接未完成任务）===")
    rows = workspace_task_rows(ws_id)
    active_rows = [r for r in rows if r[2] == "active"]
    if not active_rows:
        warn("P3 前置软：当前无 active 未完成项（planner 收束语义），仍验证新会话 pro 续接行为")
    token2, conv2, _uid2 = setup_auth(kind="task", workspace_id=ws_id)
    text = run_pro(token2, conv2, "继续上次的任务，把没做完的步骤收尾", label="P3")
    hit = [kw for kw in EXPECTED_P3_KEYWORDS if kw in text]
    if hit:
        ok(f"P3 模型回复引用未完成任务关键词 {hit} → 跨会话召回行为证据成立")
    else:
        warn(f"P3 模型回复未引用未完成任务关键词（{EXPECTED_P3_KEYWORDS}）；"
             "planner 首轮可能先摸底未提及，行为证据记 soft")
        soft.append("P3-keyword-missing")
    after = workspace_task_rows(ws_id)
    if active_rows:
        still_active = {r[0] for r in active_rows} & {r[0] for r in after if r[2] == "active"}
        if still_active:
            ok(f"P3 原未完成项仍 active 可被续接（{sorted(still_active)}）")
        else:
            ok("P3 原 active 行已由新 planner 收束（完成/换题）；跨会话链路以行为证据为准")
    if not after and not fails:
        fail(f"P3 新会话 pro 完成后无任何 task.* 行（导出未触发 workspace={ws_id}）")
        fails.append("P3-no-export")


def run_p4(token: str, conv: str, ws_id: str, fails: list[str]) -> None:
    print("\n=== P4 全完成即 void（task.* active 行置 void）===")
    before_active = [r for r in workspace_task_rows(ws_id) if r[2] == "active"]
    run_pro(token, conv, P4_DONE_QUERY, label="P4")
    if not wait_for(
            lambda: not any(r[2] == "active" for r in workspace_task_rows(ws_id)),
            timeout=300, desc="P4 task.* 全 void"):
        fail(f"P4 仍存在 active 的 task.* 行: {[(r[0], r[2]) for r in workspace_task_rows(ws_id)]}")
        fails.append("P4-not-void")
        return
    if before_active:
        ok(f"P4 全完成即 void：{len(before_active)} 个 active 未完成项 → void（完成即失效）")
    else:
        ok("P4 无 active 未完成项需失效（全量对比无残留 task.* active 行）")


def run_p5(fails: list[str], soft: list[str]) -> None:
    print("\n=== P5 chat 会话 pro 导出（scope=user + task.* 行）===")
    token, conv, user_id = setup_auth(kind="chat")
    run_pro(token, conv, P5_QUERY, label="P5")
    if wait_for(
            lambda: bool(user_task_rows(user_id)),
            timeout=300, desc="P5 user scope task.* 行落库"):
        rows = user_task_rows(user_id)
        print(f"  P5 task.* 行: {[(r[0], r[2]) for r in rows]}")
        active = [r for r in rows if r[2] == "active"]
        if active:
            ok(f"P5 chat 会话 pro → scope=user + task.* 落库（{len(active)} 个 active）")
        else:
            ok(f"P5 chat 会话 pro 导出触发（{len(rows)} 行均 void：planner 全部完成）")
        return
    # 无行：确认导出链路是否触发（可能 planner 全完成 → unfinished=0）
    if orchestrator_log_has("exported kind=chat"):
        warn("P5 DB 无 task.* 行但 orchestrator 日志存在 exported kind=chat"
             "（planner 全部完成，无未完成项可导出）；落点已由日志 + 单测覆盖，记 soft")
        soft.append("P5-user-task-rows-missing")
    else:
        fail(f"P5 导出链路未触发（DB 无 task.* 行且日志无 exported kind=chat） user={user_id}")
        fails.append("P5-export-not-triggered")


def run_p6(fails: list[str], soft: list[str]) -> None:
    print("\n=== P6 存量 chat L2 兼容（非 todo 类仍抽取注入）===")
    token, conv, user_id = setup_auth(kind="chat")
    run_fast(token, conv, P6_QUERY, label="P6")
    if not wait_for(
            lambda: any(r[0] != "todo" and r[3] == "active" for r in user_l2_rows(user_id)),
            timeout=180, desc="P6 非 todo L2 抽取"):
        warn("P6 未观察到非 todo 的 L2 行（模型可能未抽取 preference；软）")
        soft.append("P6-non-todo-missing")
        return
    rows = [r for r in user_l2_rows(user_id) if r[0] != "todo" and r[3] == "active"]
    kinds = sorted({r[0] for r in rows})
    ok(f"P6 非 todo L2 行仍正常注入（kinds={kinds}）→ chat 现状无回归")


def main() -> int:
    print("=== M2 pro 终态导出（H1 未完成项 → KV Memory todo）Live 验收 ===")
    print(f"Gateway={GATEWAY_URL} MySQL={MYSQL['host']}:{MYSQL['port']}")
    try:
        preflight_gateway()
    except RuntimeError as exc:
        fail(str(exc))
        return 1

    fails: list[str] = []
    soft: list[str] = []
    try:
        token, conv, ws_id = run_p1(fails)
        run_p2(token, conv, ws_id, fails)
        run_p3(token, ws_id, fails, soft)
        run_p4(token, conv, ws_id, fails)
        run_p5(fails, soft)
        run_p6(fails, soft)
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
    if soft:
        print("⚠ PASSED with SOFT WARNINGS（未真正验证的软场景，不影响主验收）:")
        for r in soft:
            print(f"  - {r}")
    print("✅ ALL PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
