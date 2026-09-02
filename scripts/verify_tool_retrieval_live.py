#!/usr/bin/env python3
"""phase5 5.5 工具语义检索 Live 验收 — tool RAG + retrieval 分层注入。

覆盖场景（spec phase5 §3.5.5）:
  T1   rag-service tool-index 直调：独立租户 sync → search 语义命中 + minScore 过滤
  T2   retrieval 模式端到端：chat fast 对话 → 首轮触发工具索引同步 +
       `[ToolRetrieval] 注入 Top-K 工具`（每轮动态激活组）+ 回复完成
  T3   索引幂等：同租户二次对话不再触发全量重建（指纹命中）
  T4   恒注入对照：注入日志 Top-K 仅业务工具（沙箱/HITL 元工具不分组，天然恒可见）

前置（Live 机临时灰度，验收后把 mode 改回 full）:
  1. docs/nacos/sunshine-orchestrator.yaml → agent.execution.react.tool-inject.mode: retrieval
  2. python scripts/sync_nacos.py
  3. python scripts/start.py --restart orchestrator bff
  4. 验收后 mode 改回 full，再 sync + restart

用法:
  python3 scripts/verify_tool_retrieval_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  RAG_URL    （默认 http://127.0.0.1:8400）
  ORCH_LOG   （orchestrator 日志路径，默认 ../logs/sunshine-orchestrator.log）
  TIMEOUT_SEC（单轮 SSE 上限，默认 300）
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
LOG_PATH = os.environ.get(
    "ORCH_LOG", os.path.join(os.path.dirname(__file__), "..", "logs", "sunshine-orchestrator.log"))

# 对话引导：模型应优先检索报销相关工具
T2_QUERY = "帮我把我的报销单据都查出来，并汇总最近一笔的金额"


def fail(msg: str) -> None:
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def warn(msg: str) -> None:
    print(f"  ⚠ {msg}")


def preflight() -> None:
    for name, url in (("Gateway", GATEWAY_URL), ("rag-service", RAG_URL)):
        try:
            requests.get(f"{url}/api/auth/login" if name == "Gateway" else f"{url}/actuator/health",
                         timeout=5)
        except requests.RequestException as exc:
            raise RuntimeError(f"{name} 不可达: {url} ({exc}). 请先 python scripts/start.py") from exc


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_chat() -> tuple[str, str]:
    user = f"toolr_{datetime.now():%H%M%S%f}"
    auth_json("POST", "/api/auth/register",
              {"username": user, "password": "password123", "nickname": "ToolRetrieval"}, None)
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": "password123"}, None)
    data = login.get("data") or {}
    token = data.get("token")
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


def log_line_count() -> int:
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
            return len(f.read().splitlines())
    except OSError:
        return 0


def log_new_lines(offset: int) -> list[str]:
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()[offset:]
    except OSError as exc:
        warn(f"orchestrator 日志不可读（{exc}）")
        return []


def rag_sync(tenant: str, tools: list[dict]) -> None:
    r = requests.post(f"{RAG_URL}/api/tool-index/sync", json={"tenantId": tenant, "tools": tools}, timeout=60)
    r.raise_for_status()
    body = r.json()
    if body.get("code") not in (200, None):
        raise RuntimeError(f"tool-index sync failed: {body}")


def rag_search(tenant: str, query: str, top_k: int = 5, min_score: float | None = None) -> list[dict]:
    body: dict = {"tenantId": tenant, "query": query, "topK": top_k}
    if min_score is not None:
        body["minScore"] = min_score
    r = requests.post(f"{RAG_URL}/api/tool-index/search", json=body, timeout=30)
    r.raise_for_status()
    data = r.json()
    hits = (data.get("data") or data.get("hits") or [])
    return hits or []


def gate_t1() -> None:
    """独立租户 sync → 语义命中 + minScore 过滤。"""
    tenant = f"tooltest_{uuid.uuid4().hex[:8]}"
    tools = [
        {"toolId": "sdk__sunshine-biz__list_my_expenses", "name": "查询我的报销单",
         "description": "查询当前用户提交的全部报销单据列表，含金额与审批状态", "paramsSummary": ""},
        {"toolId": "sdk__sunshine-biz__submit_leave_request", "name": "提交请假申请",
         "description": "提交员工请假申请，填写起止时间与事由", "paramsSummary": ""},
        {"toolId": "sdk__sunshine-biz__get_attendance_month", "name": "查询月度考勤",
         "description": "查询指定月份的员工出勤与打卡记录", "paramsSummary": ""},
    ]
    rag_sync(tenant, tools)

    hits = rag_search(tenant, "我有哪些报销单要处理", top_k=3)
    top = [h.get("toolId") for h in hits]
    if not top or top[0] != "sdk__sunshine-biz__list_my_expenses":
        raise RuntimeError(f"T1 语义命中失败：top={top}")
    ok(f"T1 语义命中 {top[0]}（score={hits[0].get('score')}）")

    high = [h.get("toolId") for h in rag_search(tenant, "我有哪些报销单要处理", top_k=3, min_score=0.99)]
    if high:
        raise RuntimeError(f"T1 minScore 过滤失败：高阈值仍命中 {high}")
    ok("T1 minScore 过滤（0.99 阈值无命中）")


def gate_t2_t3(offset: int, step_label: str) -> list[str]:
    token, conv_id = setup_chat()
    raw = chat_sse(token, conv_id, T2_QUERY)
    text = sse_text(raw)
    if not text.strip():
        raise RuntimeError(f"{step_label} SSE 无正文回复")
    lines = log_new_lines(offset)
    return lines


def assert_inject_evidence(lines: list[str], first_run: bool) -> None:
    sync_lines = [l for l in lines if "[ToolRetrieval] 工具索引同步完成" in l]
    inject_lines = [l for l in lines if "[ToolRetrieval] 注入 Top-K 工具" in l]
    if first_run:
        if not sync_lines:
            raise RuntimeError("首次对话未见 `[ToolRetrieval] 工具索引同步完成`（索引未同步）")
        ok(f"T2 首次触发工具索引同步 tenant=default tools={sync_lines[0].split('tools=')[-1]}")
    else:
        if sync_lines:
            raise RuntimeError(f"二次对话仍触发索引重建（指纹未生效）：{sync_lines[-1]}")
        ok("T3 索引幂等：二次对话指纹命中，未重建")
    if not inject_lines:
        raise RuntimeError("未见 `[ToolRetrieval] 注入 Top-K 工具`（动态 schema 注入未生效）")
    last = inject_lines[-1]
    ok(f"T2 动态注入 Top-K 工具: {last.split('注入 Top-K 工具:')[-1].strip()}")


def gate_t4(lines: list[str]) -> None:
    inject = [l for l in lines if "[ToolRetrieval] 注入 Top-K 工具" in l]
    banned_builtin = {"spawn_subagent", "request_decision", "search_knowledge", "think_summary"}
    for l in inject:
        for t in banned_builtin:
            if t in l:
                raise RuntimeError(f"T4 恒注入工具误入激活组: {l}")
    ok("T4 激活组仅业务工具（内置/沙箱/HITL 恒注入不分组）")


def main() -> None:
    preflight()
    print("== T1 rag-service 工具索引直调 ==")
    gate_t1()
    offset = log_line_count()
    print("\n== T2/T3/T4 retrieval 模式端到端对话（需 Nacos mode=retrieval 前置） ==")
    lines1 = gate_t2_t3(offset, "首次对话")
    assert_inject_evidence(lines1, first_run=True)
    offset2 = log_line_count()
    gate_t4(lines1)
    token, conv_id = setup_chat()
    raw = chat_sse(token, conv_id, "你好，介绍一下你自己")
    if not sse_text(raw).strip():
        raise RuntimeError("T3 二次对话无回复")
    lines2 = log_new_lines(offset2)
    assert_inject_evidence(lines2, first_run=False)
    print("\n✅ 5.5 工具语义检索 Live 验收全部通过")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        fail(str(exc))
        sys.exit(1)
