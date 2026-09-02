#!/usr/bin/env python3
"""A2A 外部智能体接入 Live 验收 — X1 / X2 / X3 / X4 / X6 / X5(skip)。

用法:
  python3 scripts/verify_external_agent_live.py            # 全量（自动拉起 mock A2A）
  python3 scripts/verify_external_agent_live.py --suite x3,x4
  python3 scripts/verify_external_agent_live.py --mock-port 9876

前置:
  - orchestrator 已重启（含 AgentExecutorRouter 分派；subagent.enabled=true）
  - mock_a2a_agent.py 可被拉起（或 --mock-port 复用已启动实例）

环境变量: GATEWAY_URL, EXTERNAL_AGENT_TIMEOUT_SEC

说明:
  X1  hard：card-prefill 拉取 Agent Card 预填 + 创建 source=EXTERNAL 智能体
  X3  hard：ReAct spawn_subagent(agent_id=外部智能体) → 子卡 label=displayName + A2A 流式正文
  X4  hard：外部智能体 endpoint 不可达 → tool result 含「外部智能体调用失败」且主 Agent 可降级
  X2  hard：$external-id 绑定 → 主 Agent spawn 外部智能体 → subagent-* 卡
  X6  soft：内部 + 外部混合 spawn（依赖模型自主判断）
  X5  skip：A2A INPUT_REQUIRED（mock 未实现，X5 由后续扩展覆盖）
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("EXTERNAL_AGENT_TIMEOUT_SEC", "240"))
MOCK_PORT = int(os.environ.get("MOCK_A2A_PORT", "9876"))
MOCK_CARD_URL = f"http://127.0.0.1:{MOCK_PORT}/.well-known/agent-card.json"


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"ext_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "ExtAgent"},
        None,
    )
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, conv_id


def new_conversation(token: str) -> str:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return conv_id


def admin_create_agent(token: str, body: dict) -> dict:
    """经 BFF 创建智能体（管理接口仅登录鉴权）。"""
    data = auth_json("POST", "/api/agents", body, token)
    if data.get("code") != 200:
        raise RuntimeError(f"create agent failed: {data}")
    return data.get("data") or {}


def admin_delete_agent(token: str, agent_id: str) -> None:
    try:
        auth_json("DELETE", f"/api/agents/{agent_id}", None, token)
    except Exception as exc:  # 清理失败不阻塞
        print(f"  [cleanup] delete {agent_id} failed: {exc}")


def card_prefill(token: str, agent_card_url: str) -> dict:
    resp = requests.get(
        f"{GATEWAY_URL}/api/agents/external/card-prefill",
        headers={"Authorization": f"Bearer {token}"},
        params={"agentCardUrl": agent_card_url},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def wait_assistant(token: str, conv_id: str, max_wait: int = 200) -> dict:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") == "completed":
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within {max_wait}s")


def chat_sse_live(token: str, conv_id: str, query: str, *, wait: bool = True) -> Any:
    collector: dict = {"steps": [], "message_status": None, "error": None, "_done": threading.Event()}

    def run() -> None:
        try:
            headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
            body = {"content": query, "conversationId": conv_id, "executionMode": "fast"}
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
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if not payload:
                        continue
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    t = obj.get("type")
                    if t == "step":
                        collector["steps"].append(obj)
                    elif t == "message" and obj.get("status"):
                        collector["message_status"] = str(obj["status"])
        except Exception as e:  # noqa: BLE001
            collector["error"] = e
        finally:
            collector["_done"].set()

    threading.Thread(target=run, daemon=True).start()
    if wait:
        if not collector["_done"].wait(TIMEOUT_SEC + 30):
            raise TimeoutError("SSE 未在超时内结束")
        if collector["error"] and not collector["steps"]:
            raise collector["error"]
    return collector


def _lifecycle_rank(step: dict) -> int:
    lc = str(step.get("lifecycle") or "")
    summary = step.get("summary") or {}
    after = str(summary.get("after") or "").strip() if isinstance(summary, dict) else ""
    if lc == "paused" and after:
        return 3
    if lc in ("done", "error", "skipped", "terminated"):
        return 2
    if lc == "paused":
        return 1
    if lc == "running":
        return 0
    return -1


def parse_assistant_steps(raw) -> list[dict]:
    if isinstance(raw, list):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, list) else []
        except json.JSONDecodeError:
            return []
    return []


def merge_steps(sse_steps: list[dict], assistant: dict) -> list[dict]:
    persisted = parse_assistant_steps(assistant.get("steps"))
    by_id: dict[str, dict] = {}
    for s in sse_steps + persisted:
        sid = str(s.get("id") or "")
        if not sid:
            continue
        prev = by_id.get(sid)
        if prev is None or _lifecycle_rank(s) >= _lifecycle_rank(prev):
            by_id[sid] = s
    if by_id:
        return list(by_id.values())
    return persisted if persisted else sse_steps


def is_subagent_step(step: dict) -> bool:
    sid = str(step.get("id") or "")
    phase = str(step.get("phase") or "")
    return phase == "subagent" or sid.startswith("subagent-")


def collect_subagent_steps(steps: list[dict]) -> list[dict]:
    return [s for s in steps if is_subagent_step(s)]


def run_x1(token: str) -> dict:
    print("\n[X1] 外部智能体注册（Agent Card 预填 + 创建）")
    pre = card_prefill(token, MOCK_CARD_URL)
    pre_data = pre.get("data") or pre
    name = str(pre_data.get("name") or "")
    desc = str(pre_data.get("description") or "")
    print(f"  prefill name={name} desc={desc[:40]}")
    hard_ok = bool(name) and ("智能体" in name or "Agent" in name or "分析" in name)
    if not hard_ok:
        print("  hint: card-prefill 未返回 Agent Card 名称，确认 mock_a2a_agent.py 已启动")
        return {"pass": False, "soft_pass": False, "prefill": pre_data}

    agent_id = f"ext-{datetime.now():%H%M%S}"
    created = admin_create_agent(token, {
        "id": agent_id,
        "displayName": "外部财务智能体",
        "description": "外部 A2A 财务分析智能体（验收用）",
        "systemPrompt": "",
        "source": "EXTERNAL",
        "agentCardUrl": MOCK_CARD_URL,
        "toolIds": [],
        "skillIds": [],
    })
    src = str(created.get("source") or "")
    ok = src == "EXTERNAL" and created.get("agentCardUrl") == MOCK_CARD_URL
    print(f"  created id={created.get('id')} source={src} card={created.get('agentCardUrl')}")
    print(f"  pass={ok}")
    return {
        "pass": ok,
        "soft_pass": ok,
        "agent_id": agent_id,
        "prefill": pre_data,
        "created": created,
    }


def _spawn_query(agent_id: str, prompt: str) -> str:
    return (
        f"请调用 spawn_subagent 元工具，agent_id={agent_id}，"
        f"prompt 写：{prompt}"
        f"主 Agent 只根据子任务返回作答，不要自己检索。"
    )


def run_x3(token: str, agent_id: str) -> dict:
    print(f"\n[X3] ReAct spawn_subagent(agent_id={agent_id}) → A2A 流式回 tool result")
    conv_id = new_conversation(token)
    query = _spawn_query(agent_id, "请分析当前财务数据并返回要点。")
    raw_col = chat_sse_live(token, conv_id, query)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(raw_col["steps"], assistant)
    sub_cards = collect_subagent_steps(steps)
    content = str(assistant.get("content") or "")
    print(f"  subagent_cards={len(sub_cards)}")
    for s in sub_cards:
        print(f"    id={s.get('id')} label={s.get('label')} lifecycle={s.get('lifecycle')}")
    print(f"  content_preview={content[:120]!r}")

    has_external_marker = "应收账款" in content or "财务" in content or "收入结构" in content
    has_card = len(sub_cards) >= 1
    hard_ok = has_card and has_external_marker
    if not hard_ok:
        print("  hint: 需 subagent-* 卡 + 正文含 mock A2A 返回要点（应收账款/收入结构）")
    return {
        "pass": hard_ok,
        "soft_pass": hard_ok,
        "subagent_count": len(sub_cards),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "content_has_external_marker": has_external_marker,
        "content_preview": content[:200],
    }


def run_x4(token: str) -> dict:
    print("\n[X4] 外部智能体不可达 → 错误降级")
    agent_id = f"ext-dead-{datetime.now():%H%M%S}"
    created = admin_create_agent(token, {
        "id": agent_id,
        "displayName": "不可达外部智能体",
        "description": "endpoint 指向未监听端口",
        "systemPrompt": "",
        "source": "EXTERNAL",
        "agentCardUrl": "http://127.0.0.1:1/.well-known/agent-card.json",
        "endpointOverride": "http://127.0.0.1:1/tasks/sendSubscribe",
        "toolIds": [],
        "skillIds": [],
    })
    print(f"  created id={created.get('id')}")

    conv_id = new_conversation(token)
    query = _spawn_query(agent_id, "请返回财务分析结果。")
    raw_col = chat_sse_live(token, conv_id, query)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    content = str(assistant.get("content") or "")
    print(f"  content_preview={content[:200]!r}")
    has_error_marker = "外部智能体调用失败" in content or "调用失败" in content or "Connection refused" in content
    hard_ok = has_error_marker
    if not hard_ok:
        print("  hint: 需正文含「外部智能体调用失败」错误信息（ExternalAgentClient onErrorResume 产物）")
    return {
        "pass": hard_ok,
        "soft_pass": hard_ok,
        "content_preview": content[:300],
        "error_in_content": has_error_marker,
    }


def run_x2(token: str, agent_id: str) -> dict:
    print(f"\n[X2] $ {agent_id} 绑定 → 主 Agent spawn 外部智能体")
    conv_id = new_conversation(token)
    query = f"${agent_id} 请分析差旅报销合规要点，直接给出结论。"
    raw_col = chat_sse_live(token, conv_id, query)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(raw_col["steps"], assistant)
    sub_cards = collect_subagent_steps(steps)
    content = str(assistant.get("content") or "")
    print(f"  subagent_cards={len(sub_cards)} content_preview={content[:120]!r}")
    hard_ok = len(sub_cards) >= 1 and bool(content)
    if not hard_ok:
        print("  hint: $ 绑定后主 Agent 需实际 spawn 外部智能体")
    return {
        "pass": hard_ok,
        "soft_pass": hard_ok,
        "subagent_count": len(sub_cards),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "content_preview": content[:200],
    }


def run_x6(token: str, agent_id: str) -> dict:
    print(f"\n[X6] 内部+外部混合 spawn（soft）")
    conv_id = new_conversation(token)
    query = (
        f"请调用 spawn_subagent 元工具并行发起两个子任务："
        f"① agent_id={agent_id}（外部），prompt=分析财务风险；"
        f"② prompt=用 search_knowledge 检索差旅制度要点（内部临时子 Agent）。"
        f"主 Agent 综合两者作答。"
    )
    raw_col = chat_sse_live(token, conv_id, query)
    assistant = wait_assistant(token, conv_id, min(TIMEOUT_SEC, 200))
    steps = merge_steps(raw_col["steps"], assistant)
    sub_cards = collect_subagent_steps(steps)
    content = str(assistant.get("content") or "")
    print(f"  subagent_cards={len(sub_cards)} content_preview={content[:120]!r}")
    soft_pass = len(sub_cards) >= 1
    return {
        "pass": True,  # 混合与否依赖模型判断，仅记录
        "soft_pass": soft_pass,
        "subagent_count": len(sub_cards),
        "subagent_ids": [str(s.get("id")) for s in sub_cards],
        "content_preview": content[:200],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="A2A 外部智能体接入 Live 验收")
    parser.add_argument("--suite", default="all", help="all / x1,x2,x3,x4,x6")
    parser.add_argument("--mock-port", type=int, default=MOCK_PORT, help="mock A2A 端口")
    parser.add_argument("--no-mock", action="store_true", help="不自动拉起 mock，复用已启动实例")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    global MOCK_PORT, MOCK_CARD_URL
    MOCK_PORT = args.mock_port
    MOCK_CARD_URL = f"http://127.0.0.1:{MOCK_PORT}/.well-known/agent-card.json"

    if args.suite.strip().lower() == "all":
        suite = ["x1", "x2", "x3", "x4", "x6"]
    else:
        suite = [x.strip().lower() for x in args.suite.split(",") if x.strip()]

    mock_proc: subprocess.Popen | None = None
    if not args.no_mock:
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock_a2a_agent.py")
        mock_proc = subprocess.Popen(
            [sys.executable, script, "--port", str(MOCK_PORT)],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        time.sleep(1.5)
        print(f"[mock] 已拉起 mock_a2a_agent.py :{MOCK_PORT}")

    print(f"=== A2A 外部智能体接入 Live ===\nGateway={GATEWAY_URL}\nsuite={suite}")
    print("[X5] A2A INPUT_REQUIRED: SKIP (mock 未实现)")
    try:
        token, conv_id = setup_auth()
        print(f"\nStep 1: auth ok conv={conv_id}")

        report: dict = {"steps": {}, "skipped": ["X5"]}
        created_id: str | None = None

        if "x1" in suite:
            x1 = run_x1(token)
            report["steps"]["X1"] = x1
            created_id = x1.get("agent_id")

        if created_id and "x3" in suite:
            report["steps"]["X3"] = run_x3(token, created_id)

        if created_id and "x2" in suite:
            report["steps"]["X2"] = run_x2(token, created_id)

        if "x4" in suite:
            report["steps"]["X4"] = run_x4(token)

        if created_id and "x6" in suite:
            report["steps"]["X6"] = run_x6(token, created_id)

        if created_id:
            admin_delete_agent(token, created_id)

        hard_failed = [k for k, v in report["steps"].items() if not v.get("soft") and not v.get("pass")]
        soft_failed = [k for k, v in report["steps"].items() if v.get("soft") and not v.get("pass")]

        print("\n=== Report ===")
        print(json.dumps(report, ensure_ascii=False, indent=2))

        if hard_failed:
            raise RuntimeError(f"hard failed: {hard_failed}")
        if soft_failed:
            print(f"\n[PASS with WARN] external agent Live; soft failed: {soft_failed}")
        else:
            print("\n[PASS] external agent Live")
        return 0
    finally:
        if mock_proc is not None:
            mock_proc.terminate()
            try:
                mock_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                mock_proc.kill()
            print("[mock] 已停止 mock_a2a_agent.py")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
