#!/usr/bin/env python3
"""智能体归一化 Live 验收 — 内部智能体 CRUD + $A $B 路由 + spawn_subagent(agent_id) + 外部智能体预填。

用法:
  python3 scripts/verify_agent_normalization_live.py
  python3 scripts/verify_agent_normalization_live.py --suite crud,routing,spawn,external
  python3 scripts/verify_agent_normalization_live.py --suite all

前置:
  - agent-manager (8235)、orchestrator (8200)、Gateway (8000) 可用
  - LLM Gateway 可用
  - 16-sunshine-agent-manager.sql 已初始化

环境变量: GATEWAY_URL, API_KEY
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
AGENT_MANAGER_URL = os.environ.get("AGENT_MANAGER_URL", "http://ecs4c16g:8235").rstrip("/")
API_KEY = os.environ.get("API_KEY", "test")
HEADERS = {"x-user-id": "live-test-user", "x-api-key": API_KEY, "Content-Type": "application/json"}

ALL_SUITES = {"crud", "routing", "spawn", "external"}


def _req(method: str, path: str, base: str = GATEWAY_URL, **kw) -> requests.Response:
    url = f"{base}{path}"
    kw.setdefault("timeout", 30)
    resp = requests.request(method, url, headers=HEADERS, **kw)
    return resp


# ---------------------------------------------------------------------
# Suite: CRUD — 智能体 CRUD + skill 绑定 + 启用/禁用
# ---------------------------------------------------------------------
def test_crud_create() -> dict:
    """T1: POST /api/agents 创建智能体"""
    print("  T1 创建智能体…")
    resp = _req("POST", "/api/agents", base=AGENT_MANAGER_URL, json={
        "id": f"test-crud-{uuid.uuid4().hex[:8]}",
        "displayName": "CRUD测试智能体",
        "description": "用于测试CRUD的临时智能体",
        "systemPrompt": "你是测试助手，简洁回答。",
        "enabled": True,
        "source": "INTERNAL",
        "maxIters": 8,
    })
    assert resp.status_code == 200, f"创建失败: {resp.status_code} {resp.text}"
    data = resp.json()["data"]
    assert data["id"] is not None
    assert data["displayName"] == "CRUD测试智能体"
    assert data["source"] == "INTERNAL"
    print(f"    → 创建成功: {data['id']}")
    return data


def test_crud_list(created: dict) -> None:
    """T2: GET /api/agents 列表含已创建智能体"""
    print("  T2 查询智能体列表…")
    resp = _req("GET", "/api/agents", base=AGENT_MANAGER_URL)
    assert resp.status_code == 200
    agent_list = resp.json()["data"]
    found = any(a["id"] == created["id"] for a in agent_list)
    assert found, f"列表中未找到已创建的智能体 {created['id']}"
    print(f"    → 列表含 {len(agent_list)} 个智能体，含目标")


def test_crud_update(created: dict) -> dict:
    """T3: PUT /api/agents/{id} 更新智能体配置"""
    print("  T3 更新智能体配置…")
    resp = _req("PUT", f"/api/agents/{created['id']}", base=AGENT_MANAGER_URL, json={
        "displayName": "CRUD测试智能体（已更新）",
        "description": "更新后的描述",
        "maxIters": 16,
        "kbScope": ["default"],
        "permissions": {"hitl": "never", "sandboxWriteMode": "always"},
    })
    assert resp.status_code == 200, f"更新失败: {resp.status_code} {resp.text}"
    data = resp.json()["data"]
    assert data["displayName"] == "CRUD测试智能体（已更新）"
    assert data.get("maxIters") == 16
    print(f"    → 更新成功")
    return data


def test_crud_enable_disable(created: dict) -> None:
    """T4: PUT /api/agents/{id}/enable 切换启用状态"""
    agent_id = created["id"]
    print("  T4a 禁用智能体…")
    resp = _req("PUT", f"/api/agents/{agent_id}/enable", base=AGENT_MANAGER_URL, json={"enabled": False})
    assert resp.status_code == 200
    assert resp.json()["data"]["enabled"] is False
    print(f"    → 禁用成功")

    print("  T4b 启用智能体…")
    resp = _req("PUT", f"/api/agents/{agent_id}/enable", base=AGENT_MANAGER_URL, json={"enabled": True})
    assert resp.status_code == 200
    assert resp.json()["data"]["enabled"] is True
    print(f"    → 启用成功")


def test_crud_delete(created: dict) -> None:
    """T5: DELETE /api/agents/{id} 删除智能体"""
    print("  T5 删除智能体…")
    resp = _req("DELETE", f"/api/agents/{created['id']}", base=AGENT_MANAGER_URL)
    assert resp.status_code == 200
    # 确认已删除
    resp2 = _req("GET", "/api/agents", base=AGENT_MANAGER_URL)
    remaining = [a for a in resp2.json()["data"] if a["id"] == created["id"]]
    assert len(remaining) == 0, f"删除后仍可查询到 {created['id']}"
    print(f"    → 删除成功")


def suite_crud() -> None:
    print("\n=== Suite: 智能体 CRUD ===")
    created = test_crud_create()
    try:
        test_crud_list(created)
        created = test_crud_update(created)
        test_crud_enable_disable(created)
    finally:
        test_crud_delete(created)
    print("✓ 智能体 CRUD 通过")


# ---------------------------------------------------------------------
# Suite: Routing — $A $B 绑定路由
# ---------------------------------------------------------------------
def test_routing_agent_binding() -> None:
    """R1: $agent-name 绑定路由到 REACT 模式"""
    print("  R1 $A 绑定路由…")
    # 使用 session ID 隔离
    session_id = f"routing-{uuid.uuid4().hex[:8]}"
    resp = _req("POST", "/api/chat/send", json={
        "message": "$policy-agent 查一下我的假期余额",
        "sessionId": session_id,
    })
    assert resp.status_code == 200, f"Chat send 失败: {resp.status_code} {resp.text}"
    print(f"    → $A 路由请求发送成功")
    print("  R1 NOTE: 需人工确认时间线中主 Agent 使用了 policy-agent 的 systemPrompt")
    return session_id


def test_routing_multiple_bindings() -> None:
    """R2: $A $B 多绑定"""
    print("  R2 多 $A $B 绑定…")
    session_id = f"routing-multi-{uuid.uuid4().hex[:8]}"
    resp = _req("POST", "/api/chat/send", json={
        "message": "$policy-agent $finance-agent 比较人事制度与费用报销标准的差异",
        "sessionId": session_id,
    })
    assert resp.status_code == 200
    print(f"    → 多绑定请求发送成功")
    return session_id


def suite_routing() -> None:
    print("\n=== Suite: $A $B 绑定路由 ===")
    sid1 = test_routing_agent_binding()
    sid2 = test_routing_multiple_bindings()
    print(f"  Sessions: {sid1}, {sid2}")
    print("✓ $A $B 路由通过 (人工确认推荐)")


# ---------------------------------------------------------------------
# Suite: Spawn — spawn_subagent(agent_id)
# ---------------------------------------------------------------------
def test_spawn_agent_id() -> None:
    """S1: spawn_subagent(agent_id) 使用预定义智能体"""
    print("  S1 spawn_subagent(agent_id)…")
    session_id = f"spawn-agent-{uuid.uuid4().hex[:8]}"
    resp = _req("POST", "/api/chat/send", json={
        "message": "$policy-agent 请先使用 spawn_subagent(agent_id=\"policy-agent\", prompt=\"用 search_knowledge 检索考勤制度\") 委派子任务，再根据结果作答。",
        "sessionId": session_id,
    })
    assert resp.status_code == 200
    print(f"    → spawn 请求发送成功")
    print("  S1 NOTE: 需人工确认子 Agent 使用了 policy-agent 的配置(systemPrompt/tools/kbScope)")
    return session_id


def test_spawn_multiple_agents() -> None:
    """S2: 并发 spawn 两个不同智能体"""
    print("  S2 并发 spawn 两个智能体…")
    session_id = f"spawn-multi-{uuid.uuid4().hex[:8]}"
    resp = _req("POST", "/api/chat/send", json={
        "message": "$policy-agent $finance-agent 请并行调用两个 spawn_subagent：① agent_id=policy-agent prompt=检索考勤制度 ② agent_id=finance-agent prompt=检索报销标准。汇总两者的结果。",
        "sessionId": session_id,
    })
    assert resp.status_code == 200
    print(f"    → 并发 spawn 请求发送成功")
    return session_id


def suite_spawn() -> None:
    print("\n=== Suite: spawn_subagent(agent_id) ===")
    sid1 = test_spawn_agent_id()
    sid2 = test_spawn_multiple_agents()
    print(f"  Sessions: {sid1}, {sid2}")
    print("✓ spawn_subagent 通过 (人工确认推荐)")


# ---------------------------------------------------------------------
# Suite: External — 外部智能体预填
# ---------------------------------------------------------------------
def test_external_card_prefill() -> None:
    """X1: GET /api/agents/external/card-prefill 拉取 Agent Card"""
    print("  X1 Agent Card 预填 API…")
    # 使用已知的公开 Agent Card
    resp = _req("GET", "/api/agents/external/card-prefill",
                base=AGENT_MANAGER_URL,
                params={"agentCardUrl": "https://example.com/.well-known/agent-card.json"})
    # 外部失败是预期的（URL 不存在），验证 API 存在且返回预填结构
    assert resp.status_code == 200, f"预填 API 失败: {resp.status_code}"
    data = resp.json()["data"]
    assert "error" in data
    print(f"    → 预填 API 正常响应 (外部 URL 不存在，返回 error: {data.get('error', '')[:60]})")


def test_external_crud_lifecycle() -> None:
    """X2: 外部智能体完整 CRUD"""
    print("  X2 外部智能体 CRUD…")
    agent_id = f"ext-test-{uuid.uuid4().hex[:8]}"
    # 创建
    resp = _req("POST", "/api/agents", base=AGENT_MANAGER_URL, json={
        "id": agent_id,
        "displayName": "外部测试智能体",
        "description": "外部A2A智能体测试",
        "systemPrompt": "你是外部智能体。",
        "source": "EXTERNAL",
        "agentCardUrl": "https://example.com/.well-known/agent-card.json",
        "endpointOverride": "https://example.com/tasks/sendSubscribe",
        "authConfig": {"type": "bearer", "token": "test-token"},
    })
    assert resp.status_code == 200, f"创建外部智能体失败: {resp.status_code} {resp.text}"
    created = resp.json()["data"]
    assert created["source"] == "EXTERNAL"
    assert created["agentCardUrl"] == "https://example.com/.well-known/agent-card.json"
    print(f"    → 外部智能体创建成功: {agent_id}")

    # 查询
    resp2 = _req("GET", "/api/agents", base=AGENT_MANAGER_URL)
    external_agents = [a for a in resp2.json()["data"] if a["source"] == "EXTERNAL"]
    assert any(a["id"] == agent_id for a in external_agents), "未找到外部智能体"
    print(f"    → 外部智能体列表含 {len(external_agents)} 个")

    # 清理
    _req("DELETE", f"/api/agents/{agent_id}", base=AGENT_MANAGER_URL)
    print(f"    → 清理完成")


def suite_external() -> None:
    print("\n=== Suite: 外部智能体 ===")
    test_external_card_prefill()
    test_external_crud_lifecycle()
    print("✓ 外部智能体通过")


# ---------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------
SUITES = {
    "crud": suite_crud,
    "routing": suite_routing,
    "spawn": suite_spawn,
    "external": suite_external,
}


def main():
    parser = argparse.ArgumentParser(description="智能体归一化 Live 验收")
    parser.add_argument("--suite", type=str, default="all",
                        help="逗号分隔的 test suite: crud,routing,spawn,external,all")
    parser.add_argument("--print-prompts", action="store_true", help="打印交互提示词")
    args = parser.parse_args()

    if args.suite == "all":
        selected = list(SUITES.values())
    else:
        selected = [SUITES[s.strip()] for s in args.suite.split(",") if s.strip() in SUITES]

    if not selected:
        print(f"未知 suite: {args.suite}. 可选: {list(SUITES.keys())}")
        sys.exit(1)

    print(f"GATEWAY_URL={GATEWAY_URL}")
    print(f"AGENT_MANAGER_URL={AGENT_MANAGER_URL}")

    passed = 0
    for suite_fn in selected:
        name = suite_fn.__name__
        try:
            suite_fn()
            passed += 1
        except Exception as e:
            print(f"\n✗ {name}  FAILED: {e}")
            if args.print_prompts:
                import traceback
                traceback.print_exc()

    print(f"\n===== 结果: {passed}/{len(selected)} suites 通过 =====")
    return 0 if passed == len(selected) else 1


if __name__ == "__main__":
    sys.exit(main())
