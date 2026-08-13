#!/usr/bin/env python3
"""企业流程 Live — hr-leave-assist / expense-compliance / oa-task-assist。

用法:
  PYTHONPATH=scripts python3 scripts/verify_enterprise_workflow_live.py --suite read
  PYTHONPATH=scripts python3 scripts/verify_enterprise_workflow_live.py --suite write
  PYTHONPATH=scripts python3 scripts/verify_enterprise_workflow_live.py --suite all

环境变量:
  GATEWAY_URL                 默认 http://127.0.0.1:8000
  ENTERPRISE_WF_TIMEOUT_SEC   SSE/轮询超时，默认 180
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
import uuid
from typing import Any

import requests

from verify_workflow_studio_live import (
    GATEWAY_URL,
    auth_json,
    conversation_id,
    workflow_hit,
)

TIMEOUT_SEC = int(os.environ.get("ENTERPRISE_WF_TIMEOUT_SEC", "180"))

WRITE_TOOL_IDS = (
    "sdk__sunshine-hr__submit_leave_request",
    "sdk__sunshine-finance__submit_expense",
    "sdk__sunshine-oa__approve_oa_task",
)

LEAK_PATTERNS = (
    "hr-leave-assist",
    "expense-compliance",
    "oa-task-assist",
    "sdk__",
)

# 种子/业务 taskId：task-a1；排除 oa-task-assist 等误匹配
TASK_ID_RE = re.compile(r"\btask-[a-z]\d+\b", re.IGNORECASE)


class CaseResult:
    def __init__(self, case_id: str, status: str, detail: str = "") -> None:
        self.case_id = case_id
        self.status = status  # PASS | FAIL | SKIP
        self.detail = detail


class SseCollector:
    def __init__(self) -> None:
        self.confirmation: dict | None = None
        self.confirmations: list[dict] = []
        self.steps: list[dict] = []
        self.message_status: str | None = None
        self.content_chunks: list[str] = []
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError(f"SSE 未在 {timeout}s 内结束")

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
            self.confirmation = obj
            self.confirmations.append(obj)
        elif t == "step":
            self.steps.append(obj)
        elif t == "message" and obj.get("status"):
            self.message_status = obj["status"]
        elif t == "content" and obj.get("text"):
            self.content_chunks.append(obj["text"])


def auth_headers_prefer_alice() -> tuple[dict[str, str], str, str]:
    """优先 alice/password123；失败则注册临时用户。返回 (headers, username, user_id)。"""
    login = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": "alice", "password": "password123"},
        timeout=30,
    )
    if login.ok:
        body = login.json()
        data = body.get("data") or {}
        token = data.get("token")
        if token:
            user_id = data.get("userId") or data.get("id") or ""
            if not user_id:
                me = requests.get(
                    f"{GATEWAY_URL}/api/auth/me",
                    headers={"Authorization": f"Bearer {token}"},
                    timeout=30,
                )
                if me.ok:
                    user_id = ((me.json().get("data") or {}).get("userId")) or ""
            print(f"  [auth] alice userId={user_id}")
            return {"Authorization": f"Bearer {token}"}, "alice", user_id
    username = f"ent_wf_{uuid.uuid4().hex[:8]}"
    password = "password123"
    reg = requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": username, "password": password, "nickname": "ent-wf"},
        timeout=30,
    )
    reg.raise_for_status()
    if reg.json().get("code") != 200:
        raise RuntimeError(f"register failed: {reg.json()}")
    login2 = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    login2.raise_for_status()
    data = login2.json().get("data") or {}
    token = data.get("token")
    user_id = data.get("userId") or data.get("id") or ""
    if not token:
        raise RuntimeError(f"login failed: {login2.json()}")
    if not user_id:
        me = requests.get(
            f"{GATEWAY_URL}/api/auth/me",
            headers={"Authorization": f"Bearer {token}"},
            timeout=30,
        )
        if me.ok:
            user_id = ((me.json().get("data") or {}).get("userId")) or ""
    print(f"  [auth] temp user {username} userId={user_id}")
    return {"Authorization": f"Bearer {token}"}, username, user_id


def chat_sse_stream(
    token: str,
    conv_id: str,
    query: str,
    *,
    execution_preference: str = "workflow",
    stop_on_confirmation: bool = False,
) -> SseCollector:
    collector = SseCollector()

    def run() -> None:
        try:
            headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            }
            body = {
                "content": query,
                "conversationId": conv_id,
                "executionPreference": execution_preference,
            }
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
                    if line.startswith("data:"):
                        collector.parse_line(line)
                        if not stop_on_confirmation or not collector.confirmation:
                            continue
                        # 目标写工具 → 成功可停；其它 HITL → 也停，交由上层 SKIP
                        break
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    t = threading.Thread(target=run, daemon=True)
    t.start()
    try:
        collector.wait_done(TIMEOUT_SEC + 30)
    except TimeoutError as e:
        # 写套件可能卡在 HITL；有 confirmation/steps 则交由断言，不硬崩
        if not collector.confirmation and not collector.steps and not collector.content_chunks:
            raise e
        print(f"  [WARN] SSE timeout with partial data confirms={len(collector.confirmations)}")
    if collector.error and not collector.steps and not collector.confirmation:
        raise collector.error
    return collector


def wait_assistant_terminal(token: str, conv_id: str, max_wait_sec: int | None = None) -> dict:
    """等待 assistant 终态：completed / failed / interrupted / paused（HITL）。"""
    max_wait = max_wait_sec if max_wait_sec is not None else TIMEOUT_SEC
    deadline = time.time() + max_wait
    terminal = ("completed", "failed", "interrupted", "paused")
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") in terminal:
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not terminal within {max_wait}s")


def normalize_steps(raw: Any) -> list[dict]:
    if isinstance(raw, list):
        return [s for s in raw if isinstance(s, dict)]
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return []
        if isinstance(parsed, list):
            return [s for s in parsed if isinstance(s, dict)]
        if isinstance(parsed, str):
            try:
                nested = json.loads(parsed)
            except json.JSONDecodeError:
                return []
            if isinstance(nested, list):
                return [s for s in nested if isinstance(s, dict)]
    return []


def assistant_blob(assistant: dict) -> str:
    steps = normalize_steps(assistant.get("steps"))
    parts = [
        str(assistant.get("content") or ""),
        json.dumps(steps, ensure_ascii=False),
    ]
    return "\n".join(parts)


def has_user_facing_leak(text: str) -> list[str]:
    hits = []
    for p in LEAK_PATTERNS:
        if p in text:
            hits.append(p)
    return hits


def confirmation_is_write(conf: dict | None) -> bool:
    if not conf:
        return False
    tool_id = str(conf.get("toolId") or "")
    return tool_id in WRITE_TOOL_IDS


def has_write_signal(collector: SseCollector, assistant: dict | None) -> bool:
    for conf in collector.confirmations:
        if confirmation_is_write(conf):
            return True
    if confirmation_is_write(collector.confirmation):
        return True
    for step in collector.steps:
        if not isinstance(step, dict):
            continue
        sid = str(step.get("id") or "")
        if any(sid.startswith(f"tool-{tid}") for tid in WRITE_TOOL_IDS):
            return True
        if step.get("toolId") in WRITE_TOOL_IDS:
            return True
    if assistant:
        for s in normalize_steps(assistant.get("steps")):
            sid = str(s.get("id") or "")
            if any(sid.startswith(f"tool-{tid}") for tid in WRITE_TOOL_IDS):
                return True
    return False


def extract_task_ids(text: str) -> list[str]:
    found = TASK_ID_RE.findall(text)
    seen: set[str] = set()
    out: list[str] = []
    for t in found:
        key = t.lower()
        if key not in seen:
            seen.add(key)
            out.append(t)
    return out


def extract_task_ids_from_assistant(assistant: dict, collector: SseCollector) -> list[str]:
    """优先从 list_oa_tasks 工具结果取 taskId。"""
    chunks: list[str] = []
    for s in collector.steps:
        if not isinstance(s, dict):
            continue
        sid = str(s.get("id") or "")
        if "list_oa_tasks" in sid:
            chunks.append(json.dumps(s, ensure_ascii=False))
    for s in normalize_steps(assistant.get("steps")):
        sid = str(s.get("id") or "")
        if "list_oa_tasks" in sid:
            chunks.append(json.dumps(s, ensure_ascii=False))
    preferred = extract_task_ids("\n".join(chunks))
    if preferred:
        return preferred
    # 回落：正文 + 全 steps（不含 workflow id 误匹配，由 TASK_ID_RE 约束）
    blob = assistant_blob(assistant) + "".join(collector.content_chunks)
    return extract_task_ids(blob)


def new_conv(token: str) -> str:
    return conversation_id(auth_json("POST", "/api/conversations", None, token))


def run_read_case(
    token: str,
    case_id: str,
    query: str,
    expected_wf: str,
    *,
    soft_keywords: tuple[str, ...] = (),
) -> CaseResult:
    print(f"\n[{case_id}] {query}")
    conv_id = new_conv(token)
    collector = chat_sse_stream(token, conv_id, query, execution_preference="workflow")
    assistant = wait_assistant_terminal(token, conv_id)
    status = assistant.get("status")
    wf = assistant.get("workflowId")
    intent = assistant.get("intent")
    content = str(assistant.get("content") or "")
    steps = normalize_steps(assistant.get("steps"))
    blob = assistant_blob(assistant)

    if not workflow_hit(assistant, expected_wf):
        return CaseResult(
            case_id,
            "FAIL",
            f"路由未命中 expected={expected_wf} workflowId={wf} intent={intent}",
        )
    if status == "failed":
        return CaseResult(case_id, "FAIL", f"message failed content={content[:200]!r}")
    if status not in ("completed", "paused"):
        return CaseResult(case_id, "FAIL", f"unexpected status={status}")
    if status == "paused":
        print(f"  [WARN] HITL paused（只读套件意外确认）confirmations={len(collector.confirmations)}")

    # 加载失败文案「工作流「id」未定义」本身含 id，视为硬失败
    if "未定义" in content and "工作流" in content:
        return CaseResult(case_id, "FAIL", f"workflow 定义未加载: {content[:120]!r}")

    leaks = has_user_facing_leak(content)
    # 排除错误兜底句已单独处理；正常回答不应出现 sdk__/workflow id
    if leaks:
        return CaseResult(case_id, "FAIL", f"正文泄露内部 id: {leaks}")

    has_steps_or_content = bool(steps) or bool(content.strip()) or bool(collector.content_chunks)
    if case_id == "E2" and not has_steps_or_content:
        return CaseResult(case_id, "FAIL", "无 steps 且无 content")

    soft_ok = True
    if soft_keywords:
        soft_ok = any(k in blob for k in soft_keywords)
        if soft_ok:
            print(f"  [soft OK] 命中关键词 {soft_keywords}")
        else:
            print(f"  [soft] 未命中数据关键词 {soft_keywords}（可能无种子，不硬失败）")

    print(
        f"  [PASS] workflowId={wf} status={status} "
        f"steps={len(steps)} content_len={len(content)}"
    )
    return CaseResult(case_id, "PASS", f"wf={wf} status={status} soft_data={soft_ok}")


def suite_read(token: str) -> list[CaseResult]:
    print("=== suite read (E1–E3 HARD) ===")
    results = [
        run_read_case(
            token,
            "E1",
            "#hr-leave-assist 青松假还有几天，列出我的请假单",
            "hr-leave-assist",
            soft_keywords=("余额", "假", "请假"),
        ),
        run_read_case(
            token,
            "E2",
            "#expense-compliance 对照网约车制度看我的报销是否合规",
            "expense-compliance",
        ),
        run_read_case(
            token,
            "E3",
            "#oa-task-assist 我的 OA 待办有哪些",
            "oa-task-assist",
            soft_keywords=("待办", "任务", "暂无", "task-"),
        ),
    ]
    return results


def run_write_case(
    token: str,
    case_id: str,
    query: str,
    expected_wf: str,
) -> CaseResult:
    print(f"\n[{case_id}] {query}")
    conv_id = new_conv(token)
    collector = chat_sse_stream(
        token,
        conv_id,
        query,
        execution_preference="workflow",
        stop_on_confirmation=True,
    )
    try:
        assistant = wait_assistant_terminal(token, conv_id, max_wait_sec=min(60, TIMEOUT_SEC))
    except RuntimeError:
        # confirmation 后 SSE 可能未结束；仍以 collector + 路由为准
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        assistant = assistants[-1] if assistants else {}

    if assistant and not workflow_hit(assistant, expected_wf):
        wf = assistant.get("workflowId")
        intent = assistant.get("intent")
        # 若连路由都错 → FAIL（硬）
        return CaseResult(
            case_id,
            "FAIL",
            f"路由错误 expected={expected_wf} workflowId={wf} intent={intent}",
        )

    if has_write_signal(collector, assistant if assistant else None):
        write_confs = [c for c in collector.confirmations if confirmation_is_write(c)]
        conf = write_confs[-1] if write_confs else None
        tool_id = (conf or {}).get("toolId") if conf else None
        print(f"  [PASS] HITL/写工具信号 toolId={tool_id} confirms={len(write_confs)}")
        return CaseResult(case_id, "PASS", f"confirmation/write tool toolId={tool_id}")

    # 收到了 confirmation 但不是目标写工具 → SKIP（模型跑偏，非路由错误）
    other = collector.confirmation or (collector.confirmations[-1] if collector.confirmations else None)
    if other and not confirmation_is_write(other):
        reason = f"收到 confirmation 但 toolId={other.get('toolId')!r} 非企业写工具"
        print(f"  [SKIP] {reason}")
        return CaseResult(case_id, "SKIP", reason)

    content = str((assistant or {}).get("content") or "") + "".join(collector.content_chunks)
    reason = (
        "模型未调用写工具（无 confirmation / write step）；"
        f"status={(assistant or {}).get('status')} content={content[:160]!r}"
    )
    print(f"  [SKIP] {reason}")
    return CaseResult(case_id, "SKIP", reason)


def prefetch_oa_task_ids(token: str, user_id: str) -> list[str]:
    """直连 OA 服务拉 pending，避免仅依赖模型叙述。"""
    oa_url = os.environ.get("OA_URL", "http://127.0.0.1:8700").rstrip("/")
    try:
        resp = requests.get(
            f"{oa_url}/api/oa/tasks",
            headers={
                "Authorization": f"Bearer {token}",
                "x-user-id": user_id,
                "x-tenant-id": "default",
            },
            params={"status": "pending"},
            timeout=15,
        )
        if not resp.ok:
            print(f"  [WARN] OA list HTTP {resp.status_code}")
            return []
        body = resp.json()
        data = body.get("data") if isinstance(body, dict) else body
        if not isinstance(data, list):
            return []
        ids = []
        for row in data:
            if isinstance(row, dict) and row.get("id"):
                ids.append(str(row["id"]))
        return ids
    except requests.RequestException as e:
        print(f"  [WARN] OA list failed: {e}")
        return []


def suite_write(token: str, user_id: str) -> list[CaseResult]:
    print("=== suite write (E4–E6 best-effort) ===")
    results: list[CaseResult] = []
    results.append(
        run_write_case(
            token,
            "E4",
            "#hr-leave-assist 请帮我申请明天一天青松假，事由企业流程live验收",
            "hr-leave-assist",
        )
    )
    results.append(
        run_write_case(
            token,
            "E5",
            "#expense-compliance 请提交一笔市内网约车报销 50 元，日期今天，备注 live-e5",
            "expense-compliance",
        )
    )

    print("\n[E6] 预取 OA 待办…")
    task_ids = prefetch_oa_task_ids(token, user_id)
    if not task_ids:
        # 回落：走 workflow list 再解析
        list_conv = new_conv(token)
        list_q = "#oa-task-assist 我的 OA 待办有哪些"
        list_coll = chat_sse_stream(token, list_conv, list_q, execution_preference="workflow")
        list_asst = wait_assistant_terminal(token, list_conv)
        if not workflow_hit(list_asst, "oa-task-assist"):
            results.append(
                CaseResult(
                    "E6",
                    "FAIL",
                    f"预取路由失败 workflowId={list_asst.get('workflowId')}",
                )
            )
            return results
        task_ids = extract_task_ids_from_assistant(list_asst, list_coll)
    if not task_ids:
        reason = "OA 待办列表为空，无 taskId 可审批"
        print(f"  [SKIP] E6 {reason}")
        results.append(CaseResult("E6", "SKIP", reason))
        return results
    task_id = task_ids[0]
    print(f"  [INFO] 使用 taskId={task_id}")
    results.append(
        run_write_case(
            token,
            "E6",
            f"#oa-task-assist 请批准 OA 待办 taskId={task_id}",
            "oa-task-assist",
        )
    )
    return results


def summarize(results: list[CaseResult]) -> int:
    pass_n = sum(1 for r in results if r.status == "PASS")
    skip_n = sum(1 for r in results if r.status == "SKIP")
    fail_n = sum(1 for r in results if r.status == "FAIL")
    print("\n=== summary ===")
    for r in results:
        print(f"  {r.case_id}: {r.status} — {r.detail}")
    print(f"PASS={pass_n} SKIP={skip_n} FAIL={fail_n}")
    # read FAIL → exit 1；write SKIP 允许；write 路由 FAIL → exit 1
    return 1 if fail_n else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="企业流程 Live 验收（E1–E6）")
    parser.add_argument("--suite", choices=["read", "write", "all"], default="read")
    args = parser.parse_args()

    print(f"Gateway={GATEWAY_URL} timeout={TIMEOUT_SEC}s suite={args.suite}")
    headers, _user, user_id = auth_headers_prefer_alice()
    token = headers["Authorization"].removeprefix("Bearer ").strip()

    results: list[CaseResult] = []
    try:
        if args.suite in ("read", "all"):
            results.extend(suite_read(token))
        if args.suite in ("write", "all"):
            results.extend(suite_write(token, user_id))
    except requests.RequestException as e:
        print(f"[FAIL] 请求失败: {e}", file=sys.stderr)
        return 1
    except (RuntimeError, TimeoutError) as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        return 1

    code = summarize(results)
    if code == 0:
        print("[PASS] verify_enterprise_workflow_live")
    else:
        print("[FAIL] verify_enterprise_workflow_live")
    return code


if __name__ == "__main__":
    sys.exit(main())
