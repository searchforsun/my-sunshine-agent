#!/usr/bin/env python3
"""fast 任务中断落板（O1）Live 验收 — memory-ledger-view §3 / §8 验收 1。

覆盖场景（brief I1–I4）:
  I1   fast 会话产生任务板：一轮含 todo_write 的 run → SSE 出现 tasks 步（任务执行中）
  I2   执行中取消 → assistant=interrupted + doFinally 落 MySQL 快照
       （persistInterruptSnapshot，items 非空且含非终态项）
  I3   同会话新发一条普通消息（不续跑）→ 恢复块注入前置成立（最近快照含未完成项）
       + 行为证据：模型回复引用未完成项关键词（块内容本身）
  I4   幂等：同一 assistant msgId 仅一条 task_board 记录（persistFinal 按 messageId upsert）

注入块由服务端确定性渲染（TaskBoardService.renderTaskListBlock），平台不暴露组装后的
LLM 请求；I3 以「最近快照含未完成项（注入前置）+ 模型回复引用未完成项关键词」双证据断言。
任一环节未达成即硬失败；模型未按任务板行事时输出 INCONCLUSIVE（exit 2），禁止静默通过。

用法:
  python3 scripts/verify_taskboard_interrupt_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  TBINTERRUPT_TIMEOUT_SEC（单轮 SSE 上限，默认 300）
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("TBINTERRUPT_TIMEOUT_SEC", "300"))
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

I1_QUERY = ("请调用 todo_write 列出下面 4 个任务并逐个执行，每完成一个就更新状态："
            "1. 计算 12*8 2. 计算 45+55 3. 判断 100 是否为偶数 4. 用一句话描述今天的天气")
I3_QUERY = "刚才进行到哪一步了？"

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
    user = f"tbint_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "TbInterrupt"}, None)
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


class StreamSession:
    """后台 SSE 消费 + generationId 捕获（取消需先拿到 generationId）。"""

    def __init__(self, token: str, conv_id: str, payload: dict) -> None:
        self.token = token
        self.payload = payload
        self.generation_id: str | None = None
        self.steps: list[dict] = []
        self.text_parts: list[str] = []
        self.message_status: str | None = None
        self.error: Exception | None = None
        self._done = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def wait_tasks_step(self, timeout: float = 90.0) -> None:
        """等待 SSE 出现 tasks 步（任务已创建、正在执行 → 此时取消才有非终态快照）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self._has_tasks_step():
                return
            if self._done.is_set():
                break
            time.sleep(0.2)
        if self.error:
            raise self.error
        raise TimeoutError("SSE 未出现 tasks 步（模型未按任务板行事）")

    def cancel_generation(self) -> None:
        if not self.generation_id:
            raise RuntimeError("未捕获 generationId，无法 cancel")
        resp = requests.post(
            f"{GATEWAY_URL}/api/generations/{self.generation_id}/cancel",
            headers={"Authorization": f"Bearer {self.token}"},
            timeout=15,
        )
        resp.raise_for_status()

    def _has_tasks_step(self) -> bool:
        for s in self.steps:
            meta = s.get("metadata") or {}
            items = meta.get("tasks") or []
            if str(s.get("id")) == "tasks" and isinstance(items, list) and items:
                return True
        return False

    def _run(self) -> None:
        try:
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers={
                    "Authorization": f"Bearer {self.token}",
                    "Content-Type": "application/json",
                },
                json=self.payload,
                stream=True,
                timeout=TIMEOUT_SEC,
            ) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines(decode_unicode=True):
                    if not line or not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if not payload or payload == "[DONE]":
                        continue
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    t = obj.get("type")
                    if t == "generation" and obj.get("id"):
                        self.generation_id = str(obj["id"])
                    elif t == "step":
                        self.steps.append(obj)
                    elif t == "message" and obj.get("status"):
                        self.message_status = str(obj["status"])
                    if t in ("content", "message", "delta"):
                        text = obj.get("text") or obj.get("content")
                        if isinstance(text, str) and text.strip():
                            self.text_parts.append(text)
        except Exception as e:
            self.error = e
        finally:
            self._done.set()


def fetch_conversation(token: str, conv_id: str) -> dict:
    data = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
    return data.get("data") or data


def last_assistant(conv: dict) -> dict | None:
    msgs = conv.get("messages") or []
    assistants = [m for m in msgs if isinstance(m, dict) and m.get("role") == "assistant"]
    return assistants[-1] if assistants else None


def wait_assistant_status(token: str, conv_id: str, expected: str, timeout: float = 30.0) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        msg = last_assistant(fetch_conversation(token, conv_id))
        if msg and msg.get("status") == expected:
            return msg
        time.sleep(0.5)
    raise TimeoutError(f"assistant 未进入 {expected} 态")


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


def snapshot_count_for_message(message_id: str) -> int:
    sql = (
        "SELECT COUNT(*) FROM task_board "
        f"WHERE message_id='{sql_escape(message_id)}'"
    )
    lines = mysql_lines(sql)
    return int(lines[0]) if lines else 0


def parse_items(items_json: str) -> list[dict]:
    try:
        data = json.loads(items_json)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def wait_for(cond, *, timeout: int, interval: float = 1.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if cond():
                return True
        except RuntimeError:
            pass
        time.sleep(interval)
    return False


def chat_sse_text(token: str, conv_id: str, query: str) -> str:
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
        parts: list[str] = []
        for line in raw.splitlines():
            line = line.strip()
            if not line.startswith("data:"):
                continue
            body = line[5:].strip()
            if not body or body == "[DONE]":
                continue
            try:
                obj = json.loads(body)
            except Exception:
                continue
            if obj.get("type") in ("content", "message", "delta"):
                text = obj.get("text") or obj.get("content")
                if isinstance(text, str) and text.strip():
                    parts.append(text)
        return "".join(parts)
    finally:
        os.unlink(tmp)


def extract_keywords(items: list[dict]) -> list[str]:
    """从未完成项 content 提取候选关键词：全句 + 按分隔符切分的「标签/细节」片段。"""
    kws: list[str] = []
    for item in items:
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        for part in re.split(r"[：:（(）)，,、。.；;]", content):
            part = part.strip()
            if len(part) >= 2 and part not in kws:
                kws.append(part)
        if content not in kws:
            kws.append(content)
    return kws


def run_i1_i2(token: str, conv_id: str, fails: list[str],
              inconclusive: list[str]) -> str | None:
    """I1+I2：发起任务 → 执行中取消 → 断言中断快照落库。返回被中断的 assistant msgId。"""
    print("\n=== I1+I2 任务执行中取消 → doFinally 落中断快照 ===")
    sess = StreamSession(token, conv_id, {
        "conversationId": conv_id,
        "content": I1_QUERY,
        "executionMode": "fast",
    })
    sess.start()
    try:
        sess.wait_tasks_step(timeout=120)
    except TimeoutError:
        fail("I1 SSE 未出现 tasks 步（模型未按任务板行事；中断落板前置不成立）")
        inconclusive.append("I1-no-tasks-step")
        return None
    ok("I1 SSE 出现 tasks 步（任务板已创建、执行中）")
    if not sess.generation_id:
        # tasks 步已到但 generation 事件尚未解析到，短暂等待
        deadline = time.time() + 10
        while time.time() < deadline and not sess.generation_id:
            time.sleep(0.2)
    if not sess.generation_id:
        fail("I2 未捕获 generationId，无法执行取消")
        fails.append("I2-no-generation-id")
        return None
    sess.cancel_generation()
    ok(f"I2 已发起取消 generation={sess.generation_id}")
    msg = wait_assistant_status(token, conv_id, "interrupted", timeout=30)
    msg_id = str(msg.get("id") or "")
    ok(f"I2 assistant 进入 interrupted 态 msg={msg_id}")
    if not msg_id:
        fail("I2 被中断消息无 id，无法按 msgId 校验快照")
        fails.append("I2-no-msg-id")
        return None
    # doFinally 中断落板：等快照行以被中断 msgId 为键出现
    if not wait_for(lambda: snapshot_count_for_message(msg_id) >= 1, timeout=60):
        fail(f"I2 中断快照未落库（task_board 无 message_id={msg_id} 行）"
             "—— O1 persistInterruptSnapshot 失效")
        fails.append("I2-snapshot-missing")
        return None
    rows = snapshot_rows(conv_id)
    items = parse_items(rows[0][1])
    non_terminal = [i for i in items
                    if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
    if rows[0][0] != msg_id:
        fail(f"I2 最近快照行 message_id={rows[0][0]} != 被中断消息 {msg_id}")
        fails.append("I2-snapshot-key-mismatch")
        return None
    if not items:
        fail("I2 中断快照 items 为空")
        fails.append("I2-snapshot-empty")
        return None
    ok(f"I2 中断快照已落库：{len(items)} 项，未完成 {len(non_terminal)} 项")
    if not non_terminal:
        # 取消前模型恰好全部完成 → 中断落板成立但恢复注入前置关闭，转正常完成语义
        warn("I2 快照已全终态（取消时机晚于任务完成；恢复注入前置不成立）")
        inconclusive.append("I2-all-terminal-before-cancel")
    return msg_id


def run_i3(token: str, conv_id: str, fails: list[str]) -> None:
    """I3：中断后新发一条普通消息 → 恢复块证据链（前置 + 行为必要条件）。"""
    print("\n=== I3 中断后新消息 → 恢复块注入证据链 ===")
    rows = snapshot_rows(conv_id)
    if not rows:
        fail("I3 前置不成立：无中断快照")
        fails.append("I3-no-snapshot")
        return
    items = parse_items(rows[0][1])
    non_terminal = [i for i in items
                    if str(i.get("status") or "").strip() not in TERMINAL_STATUSES]
    if not non_terminal:
        warn("I3 跳过：最近快照已全终态（见 I2 INCONCLUSIVE）")
        return
    ok(f"I3 注入前置：最近快照未完成项 {len(non_terminal)} 个（新消息 → 渲染恢复块）")
    text = chat_sse_text(token, conv_id, I3_QUERY)
    keywords = extract_keywords(non_terminal)
    hit = [k for k in keywords if k and k in text]
    if hit:
        ok(f"I3 模型回复引用恢复块未完成项关键词 {hit} → 注入行为证据成立")
    else:
        fail(f"I3 模型回复未引用未完成项关键词（{keywords}）→ 恢复块注入行为证据缺失；"
             f"回复开头: {text[:120].replace(chr(10), ' ')}")
        fails.append("I3-keyword-missing")


def run_i4(conv_id: str, interrupted_msg_id: str, fails: list[str]) -> None:
    """I4：幂等——被中断 msgId 的 task_board 行数恒为 1。"""
    print("\n=== I4 幂等：同一 msgId 仅一条 task_board 记录 ===")
    time.sleep(3)  # 留出可能的重复写入窗口
    count = snapshot_count_for_message(interrupted_msg_id)
    if count != 1:
        fail(f"I4 message_id={interrupted_msg_id} 的 task_board 行数={count}（期望 1）")
        fails.append("I4-duplicate-row")
    else:
        ok(f"I4 message_id={interrupted_msg_id} task_board 行数=1 → upsert 幂等成立")


def main() -> int:
    print("=== fast 任务中断落板（O1）Live 验收 ===")
    print(f"Gateway={GATEWAY_URL} MySQL={MYSQL['host']}:{MYSQL['port']}")
    try:
        preflight_gateway()
    except RuntimeError as exc:
        fail(str(exc))
        return 1

    fails: list[str] = []
    inconclusive: list[str] = []
    try:
        token, conv_id = setup_auth()
        print(f"会话 conversation={conv_id}")
        interrupted_msg_id = run_i1_i2(token, conv_id, fails, inconclusive)
        if interrupted_msg_id:
            run_i3(token, conv_id, fails)
            run_i4(conv_id, interrupted_msg_id, fails)
    except Exception as exc:  # noqa: BLE001
        fail(f"执行异常: {exc}")
        fails.append("exception")

    print("\n--- 汇总 ---")
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
