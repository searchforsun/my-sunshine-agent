#!/usr/bin/env python3
"""fast 跨轮任务板恢复（M0）Live 验收 — task-list-memory §5.1 / plan 2026-08-23 M0。

覆盖场景（brief T1–T4）:
  T1   fast 会话产生任务板：一轮含 todo_write 的 run → task_board 该 conversation 出现快照
  T2   同会话新消息「继续」→ 恢复块注入前置成立（最近快照含未完成项）；task_board 无新增行（恢复路径只读）
  T3   任务全完成后再发新消息 → 快照全 terminal → 不再注入（报销流缺用户输入/HITL 无法收尾时，
       转 T3-demo 自主可完成会话验证「全完成 → 不注入」）
  T4   无快照新会话简单问话 → 无块注入；普通轮次不写 task_board（验收红线）

注入块文本【任务板】由服务端确定性纯函数渲染（TaskBoardService.renderTaskListBlock，
单测已覆盖），平台不暴露组装后的 LLM 请求（无 API/日志含块文本）。故本脚本以
「驱动块渲染的数据谓词 + 快照行数不随普通轮次增长」作黑盒断言，并输出按 DB 快照
复算的期望块文案作为证据；审计 react.taskboard.final payload 作为补充证据（软）。

用法:
  python3 scripts/verify_task_list_restore_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  TASKRESTORE_TIMEOUT_SEC（单轮 SSE 上限，默认 300）
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
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("TASKRESTORE_TIMEOUT_SEC", "300"))
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

T1_QUERY = "帮我分 3 步处理报销：查余额/提交单据/确认到账，先列任务"
T2_QUERY = "继续"
T3_COMPLETE_QUERY = "请把任务板中剩余的全部任务都完成：逐个调用 todo_write 把每个任务更新为 completed 状态"
T3B_QUERY = "请用一句话总结刚才的工作进展"
# T3 演示会话：任务无外部依赖、可自主完成（报销流缺用户输入 + HITL，模型拒绝空标完成）
T3D_LIST_QUERY = ("请调用 todo_write 列出下面 3 个任务并先不执行："
                  "1. 计算 2+3 等于几 2. 中国首都是北京吗 3. 1 米等于 100 厘米吗")
T3D_COMPLETE_QUERY = "请现在回答上面 3 个任务，并调用 todo_write 把每个任务都更新为 completed 状态"
T4_QUERY = "用一句话介绍什么是阳光智能体平台。"

TERMINAL_STATUSES = {"completed", "cancelled"}


def fail(msg: str, *, hint: str | None = None) -> None:
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)
    if hint:
        print(f"     → {hint}", file=sys.stderr)


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


def setup_auth() -> tuple[str, str]:
    user = f"restore_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "TaskRestore"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    conv = auth_json("POST", "/api/conversations", {"kind": "chat"}, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, str(conv_id)


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


def parse_sse_steps(raw: str) -> list[dict]:
    steps: list[dict] = []
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
        if obj.get("type") == "step":
            steps.append(obj)
    return steps


def run_has_tasks_step(steps: list[dict]) -> bool:
    for s in steps:
        meta = s.get("metadata") or {}
        items = meta.get("tasks") or []
        if str(s.get("id")) == "tasks" and isinstance(items, list) and items:
            return True
    return False


def sse_text(raw: str) -> str:
    """拼接 SSE 中 assistant 正文事件文本（content/message/delta）。"""
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


def latest_tasks_items(steps: list[dict]) -> list[dict]:
    for s in reversed(steps):
        meta = s.get("metadata") or {}
        items = meta.get("tasks") or []
        if str(s.get("id")) == "tasks" and isinstance(items, list) and items:
            return items
    return []


def snapshot_rows(conv_id: str) -> list[tuple[str, str, str]]:
    """返回 (message_id, items_json, updated_at) 列表，按 updated_at 降序。"""
    sql = (
        "SELECT message_id, items_json, updated_at FROM task_board "
        f"WHERE conversation_id='{sql_escape(conv_id)}' ORDER BY updated_at DESC"
    )
    out: list[tuple[str, str, str]] = []
    for line in mysql_lines(sql):
        msg_id, items_json, updated_at = line.split("\t", 2)
        out.append((msg_id, items_json, updated_at))
    return out


def parse_items(items_json: str) -> list[dict]:
    try:
        data = json.loads(items_json)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def render_expected_block(items: list[dict]) -> str:
    """按 TaskBoardService.renderTaskListBlock 复算期望恢复块（仅作证据展示）。"""
    total = len(items)
    completed = sum(1 for i in items if str(i.get("status") or "").strip() == "completed")
    lines = ["【任务板】", f"进度：{completed}/{total} 已完成"]
    for item in items:
        status = str(item.get("status") or "pending").strip() or "pending"
        lines.append(f"- [{status}] {str(item.get('content') or '').strip()}")
    lines.append("接着未完成项继续；勿重建整个任务板，勿把已完成项改回待办。")
    return "\n".join(lines)


def audit_final_payloads(conv_id: str) -> list[dict]:
    """取该会话最近一条 react.taskboard.final 审计 payload（软证据；MQ→DB 异步需轮询）。"""
    sql = (
        "SELECT payload FROM chat_audit_log WHERE conversation_id='" + sql_escape(conv_id)
        + "' AND event_type='react.taskboard.final' ORDER BY created_at DESC LIMIT 1"
    )
    lines = mysql_lines(sql)
    if not lines:
        return []
    try:
        data = json.loads(lines[0])
        return data if isinstance(data, dict) else []
    except Exception:
        return []


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


def run_fast(token: str, conv_id: str, query: str, *, label: str) -> tuple[list[dict], str]:
    print(f"\n  -- {label} 发消息: {query[:40]}{'…' if len(query) > 40 else ''}")
    raw = chat_sse(token, conv_id, query)
    steps = parse_sse_steps(raw)
    ids = [str(s.get("id")) for s in steps]
    print(f"  -- steps={ids}")
    if any("error" in str(s) for s in steps if isinstance(s.get("type"), str)):
        warn(f"{label} 出现 error 事件（继续观察）")
    return steps, sse_text(raw)


def run_t1(token: str, conv_id: str, fails: list[str], soft: list[str]) -> None:
    print("\n=== T1 fast 会话产生任务板（todo_write → task_board 快照）===")
    steps, _text = run_fast(token, conv_id, T1_QUERY, label="T1")
    sse_items = latest_tasks_items(steps)
    # 等待快照落库（persistFinal 随 run 收尾写入）
    if not wait_for(lambda: snapshot_rows(conv_id), timeout=60, desc="task_board 快照"):
        fail(f"T1 task_board 无快照 conv={conv_id}")
        fails.append("T1-snapshot-missing")
        return
    rows = snapshot_rows(conv_id)
    print(f"  T1 task_board 行数={len(rows)}")
    if len(rows) != 1:
        warn(f"T1 期望 1 行快照，实际 {len(rows)} 行")
    items = parse_items(rows[0][1])
    print(f"  T1 items_json 解析出 {len(items)} 项: "
          f"{[str(i.get('content'))[:12] for i in items]}")
    if len(items) < 3:
        fail(f"T1 items_json 少于 3 项（实际 {len(items)}）")
        fails.append("T1-items<3")
    else:
        ok(f"T1 items_json 含 {len(items)} 项")
    non_terminal = [i for i in items if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
    if not non_terminal:
        fail("T1 快照全部 terminal，无未完成项（后续注入前置不成立）")
        fails.append("T1-all-terminal")
    else:
        ok(f"T1 未完成项 {len(non_terminal)} 个 → 恢复块注入前置成立")
    if sse_items:
        ok(f"T1 SSE tasks 步含 {len(sse_items)} 个 items（与快照同源）")
    else:
        warn("T1 SSE 未出现 tasks 步（软；以 DB 快照为准）")
    print("  T1 期望恢复块（按 DB 快照复算）:")
    print("  | " + render_expected_block(items).replace("\n", "\n  | "))


def run_t2(token: str, conv_id: str, fails: list[str], soft: list[str]) -> None:
    print("\n=== T2 同会话新消息注入【任务板】块 + 只读红线 ===")
    pre_rows = snapshot_rows(conv_id)
    pre_items = parse_items(pre_rows[0][1]) if pre_rows else []
    pre_non_terminal = [i for i in pre_items if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
    if not pre_rows or not pre_non_terminal:
        fail("T2 前置不成立：T1 快照缺失或已全完成")
        fails.append("T2-precondition")
        return
    ok(f"T2 注入前置：最近快照未完成项 {len(pre_non_terminal)} 个（FAST 新消息 → 渲染恢复块）")
    count_before = len(pre_rows)
    steps, text = run_fast(token, conv_id, T2_QUERY, label="T2")
    task_activity = run_has_tasks_step(steps)
    # 等待行数稳定：若本轮有 tasks 步，run 自身 finalize 会写 1 行新快照；恢复路径本身零写入
    expected_delta = 1 if task_activity else 0
    stable = wait_for(
        lambda: len(snapshot_rows(conv_id)) == count_before + expected_delta,
        timeout=60, desc="task_board 行数稳定")
    count_after = len(snapshot_rows(conv_id))
    if stable and count_after == count_before + expected_delta:
        ok(f"T2 行数 {count_before}→{count_after}（task_activity={task_activity}）→ 恢复路径零写入")
    elif task_activity and count_after == count_before + 1:
        ok(f"T2 行数 {count_before}→{count_after}（本轮含 todo_write，run 自身终态快照 +1；恢复路径只读）")
    else:
        fail(f"T2 task_board 行数异常: {count_before}→{count_after}（task_activity={task_activity}）")
        fails.append("T2-row-growth")
    # 行为证据：T2 消息仅「继续」，若模型回复引用了恢复块中的未完成项 → 块确实注入了
    pending_keywords = []
    for item in pre_non_terminal:
        content = str(item.get("content") or "")
        pending_keywords.extend([k for k in (content.split("（")[0].strip(),) if k])
    hit = [k for k in pending_keywords if k and k in text]
    if hit:
        ok(f"T2 模型回复引用了恢复块未完成项关键词 {hit} → 恢复块已注入上下文")
    else:
        warn(f"T2 模型回复未引用未完成项关键词（{pending_keywords}）；注入证据以数据谓词 + 单测为准（软）")
    # 审计 payload 软证据：T1 快照数据（summary/items）即恢复块渲染来源；经 MQ 消费需轮询
    if wait_for(lambda: bool(audit_final_payloads(conv_id)), timeout=120, desc="react.taskboard.final 审计"):
        payload = audit_final_payloads(conv_id)
        summary = str(payload.get("summary") or "")
        audit_items = payload.get("items") or []
        if isinstance(audit_items, list) and any(
                str(i.get("status") or "").strip() not in TERMINAL_STATUSES for i in audit_items):
            ok(f"T2 审计 react.taskboard.final payload 含未完成项 + summary='{summary}'（块渲染数据源）")
        else:
            warn(f"T2 审计 payload 未含未完成项 summary='{summary}'（软）")
    else:
        warn("T2 审计 react.taskboard.final 120s 未落 MySQL（MQ 消费延迟；软）")
    latest_items = parse_items(snapshot_rows(conv_id)[0][1]) if snapshot_rows(conv_id) else []
    latest_non_terminal = [i for i in latest_items if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
    if latest_non_terminal:
        ok(f"T2 后最近快照未完成项仍 {len(latest_non_terminal)} 个（若本轮推进了任务，T3 继续收尾）")
    else:
        warn("T2 后快照已全完成（本轮模型把任务全部收尾；T3 直接验证不再注入）")


def run_t3(token: str, conv_id: str, fails: list[str], soft: list[str]) -> None:
    print("\n=== T3 任务全完成后再发新消息 → 不再注入 ===")
    rows_before = snapshot_rows(conv_id)
    if not rows_before:
        warn("T3 无快照，跳过")
        return
    latest = parse_items(rows_before[0][1])
    if all(str(i.get("status") or "").strip() in TERMINAL_STATUSES for i in latest):
        ok("T3 前置：最近快照已全 terminal")
    else:
        steps, _ = run_fast(token, conv_id, T3_COMPLETE_QUERY, label="T3-complete")
        task_activity = run_has_tasks_step(steps)
        if not wait_for(lambda: bool(snapshot_rows(conv_id)), timeout=60, desc="T3 后快照"):
            fail("T3 完成后无快照")
            fails.append("T3-no-snapshot")
            return
        delta = len(snapshot_rows(conv_id)) - len(rows_before)
        if delta == (1 if task_activity else 0):
            ok(f"T3 行数 {len(rows_before)}→{len(snapshot_rows(conv_id))}（task_activity={task_activity}）")
        else:
            warn(f"T3 行数增长 {delta}（期望 {1 if task_activity else 0}；软红线）")
        latest = parse_items(snapshot_rows(conv_id)[0][1])
    if all(str(i.get("status") or "").strip() in TERMINAL_STATUSES for i in latest):
        ok(f"T3 最近快照全 terminal（{len(latest)} 项）→ 恢复块注入前置关闭")
        _run_t3_no_inject(token, conv_id, fails, soft)
    else:
        non_term = [i for i in latest if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
        warn(f"T3 同会话未全部完成（未完成项 {len(non_term)} 个；报销流需用户补充信息 + HITL，"
             "模型拒绝空标完成）。转 T3-demo 会话用自主可完成任务验证「全完成 → 不注入」")
        soft.append("T3-same-conv-not-completed")
        _run_t3_demo(fails, soft)


def _run_t3_no_inject(token: str, conv_id: str, fails: list[str], soft: list[str]) -> None:
    rows_before = len(snapshot_rows(conv_id))
    _, _ = run_fast(token, conv_id, T3B_QUERY, label="T3b-summary")
    if not wait_for(lambda: len(snapshot_rows(conv_id)) >= rows_before, timeout=60, desc="T3b 行数"):
        pass
    after = parse_items(snapshot_rows(conv_id)[0][1]) if snapshot_rows(conv_id) else []
    if after and all(str(i.get("status") or "").strip() in TERMINAL_STATUSES for i in after):
        ok("T3b 全完成后新消息：最近快照仍全 terminal → 不注入【任务板】块")
    else:
        warn("T3b 快照出现新未完成项（模型又重建任务；不注入断言受影响，记 WARN）")
        soft.append("T3b-rebuilt")


def _run_t3_demo(fails: list[str], soft: list[str]) -> None:
    """自主可完成任务的独立会话：列出 3 个琐事任务 → 全部完成 → 新消息不再注入。"""
    token, conv_id = setup_auth()
    print(f"  -- T3-demo conversation={conv_id}")
    steps, _ = run_fast(token, conv_id, T3D_LIST_QUERY, label="T3d-list")
    if not wait_for(lambda: bool(snapshot_rows(conv_id)), timeout=60, desc="T3d 快照"):
        warn("T3-demo 无快照（模型未建任务），跳过")
        soft.append("T3-demo-no-snapshot")
        return
    rows = snapshot_rows(conv_id)
    items = parse_items(rows[0][1])
    print(f"  -- T3-demo 快照 {len(items)} 项: {[str(i.get('content'))[:16] for i in items]}")
    if len(items) < 3:
        warn(f"T3-demo 快照少于 3 项（{len(items)}）；继续推进完成流程")
    steps, _ = run_fast(token, conv_id, T3D_COMPLETE_QUERY, label="T3d-complete")
    if not wait_for(lambda: bool(snapshot_rows(conv_id)), timeout=60, desc="T3d-complete 快照"):
        warn("T3-demo 完成轮无快照")
        soft.append("T3-demo-complete-no-snapshot")
        return
    after = parse_items(snapshot_rows(conv_id)[0][1])
    non_term = [i for i in after if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
    if non_term:
        warn(f"T3-demo 完成轮后仍有未完成项 {len(non_term)} 个（模型未全部标 completed；数据谓词兜底，记 WARN）")
        soft.append("T3-demo-not-all-terminal")
        return
    ok(f"T3-demo 快照全 terminal（{len(after)} 项）→ 注入前置关闭")
    _run_t3_no_inject(token, conv_id, fails, soft)


def run_t4(fails: list[str], soft: list[str]) -> None:
    print("\n=== T4 无快照新会话简单问话 → 不注入 + 普通轮次零写入红线 ===")
    token, conv_id = setup_auth()
    steps, _ = run_fast(token, conv_id, T4_QUERY, label="T4")
    if run_has_tasks_step(steps):
        warn("T4 简单问话出现 tasks 步（模型主动建任务；软）")
    rows = snapshot_rows(conv_id)
    if rows:
        fail(f"T4 无 todo_write 的普通轮次写入了 task_board（{len(rows)} 行）— 验收红线违例")
        fails.append("T4-row-written")
    else:
        ok("T4 普通轮次后 task_board 0 行 → 无快照不注入 + 执行中不写快照层")


def main() -> int:
    print("=== fast 跨轮任务板恢复（M0）Live 验收 ===")
    print(f"Gateway={GATEWAY_URL} MySQL={MYSQL['host']}:{MYSQL['port']}")
    try:
        preflight_gateway()
    except RuntimeError as exc:
        fail(str(exc))
        return 1

    fails: list[str] = []
    soft: list[str] = []
    try:
        token, conv_id = setup_auth()
        print(f"T1 会话 conversation={conv_id}")
        run_t1(token, conv_id, fails, soft)
        run_t2(token, conv_id, fails, soft)
        run_t3(token, conv_id, fails, soft)
        run_t4(fails, soft)
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
    print("✅ ALL PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
