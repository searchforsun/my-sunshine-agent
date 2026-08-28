#!/usr/bin/env python3
"""skill-sticky（可发现/触发分离 + 轻 sticky）Live 验收 — spec 2026-08-12-skill-sticky-process-chain-design.md。

覆盖场景（S-0/S-1 验收 V0/V2/V4/V5/V19 的数据面断言）:
  T1  /skill 显式触发 → 最新 assistant 消息 routing_skill_ids 落库（S-0 完整 RoutingResult）
  T2  同会话「继续」无 / → 新 assistant 消息 routing_skill_ids 继承上轮（S-1 轻 sticky）
  T3  /skill 换技能 → routing_skill_ids 整表替换（L0 替换语义）
  T4  $agent 显式 → routing_agent_ids 落库（可调度池）
  T5  同会话「继续」无新候选 → routing_agent_ids 继承上轮（agentIds 跨轮 sticky）
  T6  无任何触发的新消息 → routing 列保持 null（无触发不落空串）

提示词层断言（V0/V3：无 L0 不灌 overlay / 目录仅名+描述）由 PromptComposer 单测覆盖，
平台不暴露组装后请求，Live 仅以数据面 + 行为面验证触发/sticky/替换语义。

用法:
  python3 scripts/verify_skill_sticky_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  SKILLSTICKY_TIMEOUT_SEC（单轮 SSE 上限，默认 300）
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("SKILLSTICKY_TIMEOUT_SEC", "300"))
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

T1_QUERY = "/finance-analysis 我本月报销还有哪些未到账"
T2_QUERY = "继续"
T3_QUERY = "/policy-review 请事假合规吗"
T4_QUERY = "$policy-agent 帮我查差旅报销制度"
T5_QUERY = "继续"
T6_QUERY = "你好"

PASS = 0
FAIL = 0


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def fail(msg: str) -> None:
    global FAIL
    FAIL += 1
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)


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


def setup_auth() -> tuple[str, str]:
    user = f"sticky_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "SkillSticky"}, None)
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


def latest_routing(conv_id: str) -> tuple[str | None, str | None]:
    """最新一条非 STREAMING assistant 消息的 routing 列（routing_skill_ids, routing_agent_ids）。"""
    rows = mysql_lines(
        "SELECT routing_skill_ids, routing_agent_ids FROM sunshine_chat.chat_message "
        "WHERE conversation_id = '%s' AND role = 'assistant' AND status <> 'streaming' "
        "ORDER BY seq DESC LIMIT 1" % sql_escape(conv_id))
    if not rows:
        return None, None
    parts = rows[0].split("\t")
    skills = parts[0] if len(parts) > 0 and parts[0] and parts[0] != "NULL" else None
    agents = parts[1] if len(parts) > 1 and parts[1] and parts[1] != "NULL" else None
    return skills, agents


def assert_skills(conv_id: str, expected: str | None, label: str) -> None:
    skills, agents = latest_routing(conv_id)
    if expected is None:
        if skills is None:
            ok(f"{label}: routing_skill_ids 为空")
        else:
            fail(f"{label}: 期望无 skill 触发，实际 routing_skill_ids={skills}")
    elif skills == expected:
        ok(f"{label}: routing_skill_ids={skills}")
    else:
        fail(f"{label}: 期望 routing_skill_ids={expected}，实际={skills}")


def assert_agents(conv_id: str, expected: str | None, label: str) -> None:
    skills, agents = latest_routing(conv_id)
    if expected is None:
        if agents is None:
            ok(f"{label}: routing_agent_ids 为空")
        else:
            fail(f"{label}: 期望无可调度 agent，实际 routing_agent_ids={agents}")
    elif agents == expected:
        ok(f"{label}: routing_agent_ids={agents}")
    else:
        fail(f"{label}: 期望 routing_agent_ids={expected}，实际={agents}")


def main() -> None:
    global PASS
    try:
        resp = requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
        _ = resp.status_code
    except requests.RequestException as exc:
        raise RuntimeError(
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). 请先 python scripts/start.py"
        ) from exc

    token, conv_id = setup_auth()
    print(f"会话: {conv_id}")

    print(f"\n[T1] {T1_QUERY}")
    body = chat_sse(token, conv_id, T1_QUERY)
    assert_skills(conv_id, "finance-analysis", "T1 /skill 触发落库")
    text = sse_text(body)
    if text.strip():
        ok("T1 产出正文")
    else:
        fail("T1 无正文输出")

    print(f"\n[T2] {T2_QUERY}（无 /，应继承上轮 triggered）")
    chat_sse(token, conv_id, T2_QUERY)
    assert_skills(conv_id, "finance-analysis", "T2 sticky 继承")

    print(f"\n[T3] {T3_QUERY}（换技能，L0 整表替换）")
    chat_sse(token, conv_id, T3_QUERY)
    assert_skills(conv_id, "policy-review", "T3 L0 替换")

    print(f"\n[T4] {T4_QUERY}（$agent 显式可调度池）")
    chat_sse(token, conv_id, T4_QUERY)
    assert_agents(conv_id, "policy-agent", "T4 $agent 落库")

    print(f"\n[T5] {T5_QUERY}（无新候选，agentIds 跨轮继承）")
    chat_sse(token, conv_id, T5_QUERY)
    assert_agents(conv_id, "policy-agent", "T5 agentIds sticky 继承")

    print(f"\n[T6] {T6_QUERY}（纯聊天，无新触发 → skill 继承不清空）")
    chat_sse(token, conv_id, T6_QUERY)
    assert_skills(conv_id, "policy-review", "T6 无新触发 skill 继承（轻 sticky 不清空）")
    assert_agents(conv_id, "policy-agent", "T6 agentIds 仍继承（无新候选不清空）")

    print(f"\n[T7] 全新会话普通消息（无 seed → 两列均不落空串）")
    conv2 = auth_json("POST", "/api/conversations", {"kind": "chat"}, token)
    conv2_id = str((conv2.get("data") or conv2).get("id"))
    chat_sse(token, conv2_id, T6_QUERY)
    assert_skills(conv2_id, None, "T7 无 seed 会话 skill 不落空串")
    assert_agents(conv2_id, None, "T7 无 seed 会话 agent 不落空串")

    print()
    if FAIL:
        print(f"❌ FAILED: {FAIL} 项未通过", file=sys.stderr)
        sys.exit(1)
    print("✅ ALL PASSED: skill-sticky S-0/S-1 Live 验收通过")
    sys.exit(0)


if __name__ == "__main__":
    main()
