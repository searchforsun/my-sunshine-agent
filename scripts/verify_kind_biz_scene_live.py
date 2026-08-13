#!/usr/bin/env python3
"""kind-biz-scene-catalog Live 验收 — 工具集按 kind 分装 + 资源 kind 元数据 + biz-scene Lab 绑定。

用法:
  python3 scripts/verify_kind_biz_scene_live.py
  GATEWAY_URL=http://127.0.0.1:8000 python3 scripts/verify_kind_biz_scene_live.py

环境变量:
  GATEWAY_URL              默认 http://127.0.0.1:8000

门禁（plan 2026-08-13-kind-biz-scene-catalog.md Task 10 / spec §9）:
  V0  工具集 Admin `sets/chat` / `sets/task` 成员非空（K0：按 kind 分装；
      Runtime 双读兼容由 ToolSetResolverTest 单测锁定，Live 只断言新 set 有成员）
  V1  资源 catalog 存在 kind=chat 与 kind=task 条目，且含 biz_scene 打标条目；
      意图候选过滤发生在 orchestrator 内存（无 HTTP 调试接口），
      task 会话召回不含 chat-only 由 ResourceKindFilterTest / IntentRouterTest 单测覆盖 → SKIP。
  V2  Lab 创建码 → Skill 绑码成功；绑定 retired 码失败（K2）
"""
from __future__ import annotations

import os
import sys
import uuid

import requests

from sunshine_lib import unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT = 30


def auth_headers() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"kind_biz_{suffix}"
    password = "password123"
    reg = requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": username, "password": password},
        timeout=30,
    )
    reg.raise_for_status()
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
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, timeout=TIMEOUT, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def gate_v0(headers: dict) -> None:
    for kind in ("chat", "task"):
        page = api_json(
            "GET",
            f"/api/admin/tools/sets/{kind}/members?page=1&size=50&tenantId=default",
            headers,
        )
        items = page.get("items") or []
        if not items:
            raise RuntimeError(f"V0 {kind} 工具集成员为空（K0 未生效或 tool-service 未重启）")
        ids = [i.get("toolId") for i in items if isinstance(i, dict)]
        print(f"  [OK] V0 sets/{kind} 成员 {len(ids)} 个: {', '.join(ids[:6])}{'…' if len(ids) > 6 else ''}")


def gate_v1(headers: dict) -> None:
    index = api_json("GET", "/api/skills/catalog/index", headers)
    if not isinstance(index, list):
        raise RuntimeError(f"V1 skill catalog index 响应非列表: {index}")
    kinds = {s.get("kind") for s in index if isinstance(s, dict)}
    if "chat" not in kinds:
        raise RuntimeError(f"V1 skill catalog 缺少 chat 资源: kinds={sorted(kinds)}")
    with_scene = [s.get("id") for s in index if isinstance(s, dict) and s.get("bizScene")]
    if not with_scene:
        raise RuntimeError("V1 skill catalog 无 biz_scene 打标资源（K2 元数据未生效或 resource-manager 未重启）")
    print(f"  [OK] V1 skill catalog kinds={sorted(kinds)}；biz_scene 打标 {len(with_scene)} 个: {', '.join(with_scene[:5])}")

    # task 资源种子不在 init（管理面自建）；动态建一条验证 kind=task 元数据（listAll 实时；catalog 缓存预热后亦可见）
    suffix = uuid.uuid4().hex[:6]
    task_skill_id = f"live-task-skill-{suffix}"
    api_json(
        "POST",
        "/api/skills",
        headers,
        json={"id": task_skill_id, "displayName": f"Live Task Skill {suffix}", "kind": "task"},
    )
    skills = api_json("GET", "/api/skills", headers)
    entry = next((s for s in skills if isinstance(s, dict) and s.get("id") == task_skill_id), None)
    if not entry or entry.get("kind") != "task":
        raise RuntimeError(f"V1 task 资源 kind 元数据未生效: {entry}")
    print(f"  [OK] V1 动态创建 kind=task 资源 {task_skill_id}（listAll 实时可见）")
    print("  [SKIP] V1 意图候选过滤（task 会话不含 chat-only）为 orchestrator 内存逻辑，无 HTTP 调试接口；"
          "由 ResourceKindFilterTest / IntentRouterTest 单测覆盖")


def gate_v2(headers: dict) -> None:
    suffix = uuid.uuid4().hex[:6]
    code = f"live-scene-{suffix}"
    skill_id = f"live-skill-{suffix}"
    retired_skill_id = f"live-skill-retired-{suffix}"

    created = api_json(
        "POST",
        "/api/biz-scenes",
        headers,
        json={"bizScene": code, "displayName": f"Live {suffix}", "description": "verify_kind_biz_scene_live.py"},
    )
    status = created.get("status") if isinstance(created, dict) else None
    if status != "active":
        raise RuntimeError(f"V2 创建码状态异常: {created}")
    print(f"  [OK] V2 Lab 创建码 {code} status=active")

    api_json(
        "POST",
        "/api/skills",
        headers,
        json={"id": skill_id, "displayName": f"Live Skill {suffix}", "kind": "chat", "bizScene": code},
    )
    skills = api_json("GET", "/api/skills", headers)
    bound = next((s.get("bizScene") for s in skills if isinstance(s, dict) and s.get("id") == skill_id), None)
    if bound != code:
        raise RuntimeError(f"V2 Skill 绑码失败: bizScene={bound}")
    print(f"  [OK] V2 Skill {skill_id} 绑定 {code} 成功")

    api_json(
        "PUT",
        f"/api/biz-scenes/{code}",
        headers,
        json={"status": "retired"},
    )
    print(f"  [OK] V2 码 {code} 置为 retired")

    failed = False
    try:
        resp = requests.post(
            f"{GATEWAY_URL}/api/skills",
            headers=headers,
            json={"id": retired_skill_id, "displayName": f"Live Retired {suffix}", "kind": "chat", "bizScene": code},
            timeout=TIMEOUT,
        )
        if resp.status_code == 200:
            unwrap_r(resp.json(), context=f"bind retired {code}")
        elif resp.status_code in (400, 409, 422):
            body = resp.json()
            if "biz_scene_not_active" not in (body.get("errorKey") or ""):
                raise RuntimeError(f"绑定 retired 码返回非预期错误: {body}")
            failed = True
        else:
            resp.raise_for_status()
    except requests.HTTPError as exc:
        raise RuntimeError(f"绑定 retired 码请求失败: {exc}") from exc
    if not failed:
        raise RuntimeError(f"V2 绑定 retired 码 {code} 未按预期拒绝")
    print(f"  [OK] V2 绑定 retired 码被拒绝（SCENE_NOT_ACTIVE）")


def main() -> None:
    headers = auth_headers()
    print(f"gateway={GATEWAY_URL}")
    for gate in (gate_v0, gate_v1, gate_v2):
        gate(headers)
    print("\nV0–V2 全部 PASS（V1 候选过滤由单测覆盖）")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"\nFAIL: {exc}", file=sys.stderr)
        sys.exit(1)
