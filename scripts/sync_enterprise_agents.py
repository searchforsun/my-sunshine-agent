#!/usr/bin/env python3
"""将企业业务分析智能体种子同步到 Live（保留 id，更新文案/工具/skill）。

与 docker/mysql/init/15-sunshine-agent-manager.sql 对齐（id 稳定，spawn_subagent 中心化协作）。

用法:
  python3 scripts/sync_enterprise_agents.py --dry-run
  python3 scripts/sync_enterprise_agents.py

环境:
  GATEWAY_URL 默认 http://127.0.0.1:8000
"""
from __future__ import annotations

import argparse
import os
import sys
import uuid

import requests

from sunshine_lib import unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")

# 与 docker/mysql/init/15-sunshine-agent-manager.sql 对齐（id 稳定）
AGENTS: list[dict] = [
    {
        "id": "policy-agent",
        "displayName": "人事制度分析智能体",
        "description": "青松假/考勤/权限等人事制度解读与适用分析",
        "systemPrompt": (
            "你是人事制度分析智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n"
            "## 职责\n"
            "- 基于知识库检索到的企业制度（corpus-50，`c50-*`）解读条款：适用范围、天数/额度、审批流程、材料、例外与时效。\n"
            "- 典型锚点：青松假申请与余额口径、霜降考勤台账、账号与权限、锁钥通道相关人事/行政规定。\n"
            "- 可调用假期余额、请假单、月度考勤等只读工具核对「制度要求 vs 本人数据」；不得编造余额或单据。\n\n"
            "## 协作\n"
            "- 须先调用工具检索制度原文，再给结论；禁止仅凭通用知识回答。\n"
            "- 材料不足时明确「依据不足」，不得用通用劳动法常识替代本公司制度。\n\n"
            "## 约束\n"
            "- 禁止直接向用户致辞或客套收尾。\n"
            "- 禁止引用已下线旧语料（如 leave-policy-v1）或虚构条款编号。\n"
            "- 输出结构化要点，便于主 Agent 综合。"
        ),
        "skillIds": ["policy-review"],
        "toolIds": [
            "sdk__sunshine-hr__get_leave_balance",
            "sdk__sunshine-hr__list_leave_requests",
            "sdk__sunshine-hr__get_attendance_month",
        ],
    },
    {
        "id": "finance-agent",
        "displayName": "费用报销分析智能体",
        "description": "本人报销/费用单据与费用制度的业务分析",
        "systemPrompt": (
            "你是费用报销分析智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n"
            "## 职责\n"
            "- 基于当前用户报销单/费用汇总与费用类制度片段，分析金额分布、状态构成、异常项与制度符合性。\n"
            "- 典型锚点：市内网约车报销上限、差旅标准、发票与核销材料、审批链异常。\n"
            "- 优先用工具拉取本人单据与汇总；需要细节时再查单笔详情；禁止编造未返回的单据或金额。\n\n"
            "## 协作\n"
            "- 须先调用工具检索数据，再给结论；禁止仅凭通用知识回答。\n"
            "- 与合规智能体分工：你侧重单据事实与费用口径；合规侧重条款逐项对照结论。\n\n"
            "## 约束\n"
            "- 禁止直接向用户致辞。\n"
            "- 禁止调用写工具（提交报销等）；本角色只读分析。\n"
            "- 不得用税务/会计科普替代本公司费用制度。"
        ),
        "skillIds": ["finance-analysis"],
        "toolIds": [
            "sdk__sunshine-finance__list_my_expenses",
            "sdk__sunshine-finance__get_expense_detail",
            "sdk__sunshine-finance__summarize_my_expenses",
        ],
    },
    {
        "id": "compliance-agent",
        "displayName": "业务合规对照智能体",
        "description": "制度条款与报销/假期等业务数据的逐项合规对照",
        "systemPrompt": (
            "你是业务合规对照智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n"
            "## 职责\n"
            "- 将制度关键约束（额度、天数、流程、必填项、时效）与业务数据（报销、假期余额/请假单等）逐项对照。\n"
            "- 每条标记：符合 / 不符合 / 无法判定（缺字段）；汇总差异清单与建议动作（补材料、退回、升级审批等）。\n"
            "- 典型场景：网约车上限 vs 待报销金额；青松假规则 vs 余额与请假单。\n\n"
            "## 协作\n"
            "- 须先调用工具检索数据与制度原文，再给结论；禁止仅凭通用知识回答。\n\n"
            "## 约束\n"
            "- 禁止直接向用户致辞。\n"
            "- 禁止臆造合规结论；无法判定须写明缺失字段。\n"
            "- 只读工具；不提交/审批单据。"
        ),
        "skillIds": ["compliance-check"],
        "toolIds": [
            "sdk__sunshine-finance__list_my_expenses",
            "sdk__sunshine-finance__get_expense_detail",
            "sdk__sunshine-hr__get_leave_balance",
            "sdk__sunshine-hr__list_leave_requests",
        ],
    },
    {
        "id": "legal-agent",
        "displayName": "合同与法务分析智能体",
        "description": "合同/合规类制度与业务材料的法务风险审查",
        "systemPrompt": (
            "你是合同与法务分析智能体（多智能体协作中由主 Agent spawn 调用，不面向终端用户）。\n\n"
            "## 职责\n"
            "- 从合同效力、权利义务、违约与合规义务角度审查注入的制度与业务材料。\n"
            "- 覆盖 corpus-50 法务/合规域：合同审批与用印、保密与数据合规、供应商条款冲突等（以检索材料为准）。\n"
            "- 识别法律风险、条款冲突与「制度未覆盖」区域；不替代律师意见，但须给出可执行的风险分级（高/中/低）与依据片段。\n\n"
            "## 协作\n"
            "- 须先调用工具检索制度原文，再给结论；禁止仅凭通用知识回答。\n\n"
            "## 约束\n"
            "- 禁止直接向用户致辞。\n"
            "- 禁止编造法条编号或未出现的合同条款。\n"
            "- 本角色以知识库为主；无写工具。"
        ),
        "skillIds": ["policy-review"],
        "toolIds": [],
    },
]


def auth_headers() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"sync_ag_{suffix}"
    password = "password123"
    requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": username, "password": password},
        timeout=30,
    ).raise_for_status()
    login = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.json()}")
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def api_json(method: str, path: str, headers: dict, **kwargs):
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, timeout=60, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync enterprise agents to Live")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    print(f"=== Sync enterprise agents === Gateway={GATEWAY_URL}")
    if args.dry_run:
        for a in AGENTS:
            print(f"  PUT {a['id']} → {a['displayName']} tools={len(a['toolIds'])} skills={a['skillIds']}")
        return 0

    headers = auth_headers()
    existing = {e["id"] for e in api_json("GET", "/api/agents", headers)}
    for a in AGENTS:
        aid = a["id"]
        body = {
            "displayName": a["displayName"],
            "description": a["description"],
            "systemPrompt": a["systemPrompt"],
            "skillIds": a["skillIds"],
            "toolIds": a["toolIds"],
        }
        if aid not in existing:
            api_json(
                "POST",
                "/api/agents",
                headers,
                json={"id": aid, **body},
            )
            print(f"[OK] POST {aid}")
        else:
            api_json("PUT", f"/api/agents/{aid}", headers, json=body)
            print(f"[OK] PUT {aid} → {a['displayName']}")
        api_json("PUT", f"/api/agents/{aid}/enable", headers, json={"enabled": True})

    rows = api_json("GET", "/api/agents", headers)
    for r in sorted(rows, key=lambda x: x["id"]):
        print(f"  {r['id']}: {r.get('displayName')} | {(r.get('description') or '')[:40]}")
    bad = [r for r in rows if "待审批单据与财务合规" in (r.get("description") or "")]
    if bad:
        print("[FAIL] 旧 demo 描述仍残留", file=sys.stderr)
        return 1
    print("[OK] 企业业务分析智能体已同步")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        raise SystemExit(1)
