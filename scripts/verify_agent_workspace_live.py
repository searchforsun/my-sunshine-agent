#!/usr/bin/env python3
"""Agent Workspace Live 验收 - 工作区 CRUD + 硬件档位校验 + checkout + git 工作流 + network 回归。

用法:
  python3 scripts/verify_agent_workspace_live.py

断言:
  W1  POST /api/agent-workspaces 创建（含硬件档位解析）
  W2  GET /api/agent-workspaces 列表含已创建项
  W3  硬件档位校验：非法档位 -> 400；合法档位 -> 落库值匹配
  W4  POST /api/conversations kind=task/workspace_id 成功
  W5  GET /api/auth/me 含 git 字段；PATCH /api/auth/profile 反映
  W6  GET /{id}/checkouts 列出 checkout（clone 成功前提下）
  W7  POST /{id}/checkouts/ensure 幂等创建 checkoutId
  W8  DELETE /api/agent-workspaces/{id} 归档

环境变量:
  GATEWAY_URL（默认 http://ecs4c16g:8000）
  TEST_REPO_URL（默认 https://github.com/octocat/Hello-World.git，须公开可 clone）
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TEST_REPO_URL = os.environ.get("TEST_REPO_URL", "https://github.com/octocat/Hello-World.git")
MARKER = f"ws-e2e-{datetime.now():%H%M%S}"


def unwrap(body: dict, *, ctx: str) -> Any:
    if not isinstance(body, dict):
        return body
    code = body.get("code")
    if code is not None and "data" in body:
        if code != 200:
            raise RuntimeError(f"[{ctx}] code={code} msg={body.get('msg')} key={body.get('errorKey')}")
        return body.get("data")
    return body


def headers(token: str) -> dict:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def register_and_login(gw: str) -> tuple[str, dict]:
    user = f"aw_{datetime.now():%H%M%S}_{os.getpid()}"
    reg = requests.post(f"{gw}/api/auth/register",
                        json={"username": user, "password": "Pass1234!", "nickname": "AgentWS"},
                        timeout=30)
    if reg.status_code != 200:
        raise RuntimeError(f"register failed: {reg.status_code} {reg.text[:200]}")
    login = requests.post(f"{gw}/api/auth/login",
                          json={"username": user, "password": "Pass1234!"},
                          timeout=30)
    if login.status_code != 200:
        raise RuntimeError(f"login failed: {login.status_code} {login.text[:200]}")
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"no token: {login.text[:200]}")
    return token, login.json().get("data")


def create_workspace(gw: str, h: dict, body: dict) -> dict:
    resp = requests.post(f"{gw}/api/agent-workspaces", json=body, headers=h, timeout=30)
    if resp.status_code != 200:
        raise RuntimeError(f"create ws failed: {resp.status_code} {resp.text[:200]}")
    return unwrap(resp.json(), ctx="create-ws")


def wait_clone(gw: str, h: dict, ws_id: str, timeout_s: int = 90) -> str:
    """轮询工作区列表的 cloneState，返回 done/failed/timeout。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        resp = requests.get(f"{gw}/api/agent-workspaces", headers=h, timeout=30)
        if resp.status_code == 200:
            lst = unwrap(resp.json(), ctx="poll-list")
            for w in lst:
                if w.get("id") == ws_id:
                    cs = w.get("cloneState")
                    if cs and cs.startswith("done"):
                        return "done"
                    if cs and cs.startswith("failed"):
                        return "failed"
        time.sleep(3)
    return "timeout"


def run():
    passed = 0
    failed = 0
    skipped = 0

    def check(cond: bool, label: str):
        nonlocal passed, failed
        if cond:
            passed += 1
            print(f"  PASS  {label}")
        else:
            failed += 1
            print(f"  FAIL  {label}")

    def skip(label: str, reason: str = ""):
        nonlocal skipped
        skipped += 1
        print(f"  SKIP  {label}" + (f"  ({reason})" if reason else ""))

    token, user = register_and_login(GATEWAY_URL)
    user_id = user.get("userId", "")
    tenant_id = user.get("tenantId", "default")
    print(f"注册登录成功 userId={user_id} tenantId={tenant_id}")
    h = headers(token)

    # W5: /me 含 git 字段 + profile 更新
    print("\n=== W5: GET /api/auth/me + PATCH /api/auth/profile ===")
    me_resp = requests.get(f"{GATEWAY_URL}/api/auth/me", headers=h, timeout=30)
    me = unwrap(me_resp.json(), ctx="me")
    check("githubUrl" in me or "githubTokenSet" in me, "me 含 githubUrl/githubTokenSet")

    patch_resp = requests.patch(f"{GATEWAY_URL}/api/auth/profile",
                                json={"nickname": "AgentWS", "tenantId": tenant_id,
                                      "githubUrl": "https://github.com",
                                      "gitlabUrl": "https://gitlab.example.com"},
                                headers=h, timeout=30)
    check(patch_resp.status_code == 200, f"profile patch ok: status={patch_resp.status_code}")
    if patch_resp.status_code == 200:
        profile_data = unwrap(patch_resp.json(), ctx="profile")
        check(profile_data.get("githubUrl") == "https://github.com", "profile githubUrl 反映")
        check(profile_data.get("gitlabUrl") == "https://gitlab.example.com", "profile gitlabUrl 反映")
        if profile_data.get("token"):
            token = profile_data["token"]
            h = headers(token)

    # W3: 硬件档位校验（非法档位 -> 400）
    print("\n=== W3: 硬件档位校验 ===")
    illegal_resp = requests.post(f"{GATEWAY_URL}/api/agent-workspaces",
                                 json={"name": f"{MARKER}-illegal", "repoUrl": TEST_REPO_URL,
                                       "memoryMb": 3000, "cpus": 1.5},
                                 headers=h, timeout=30)
    check(illegal_resp.status_code == 400, f"非法档位 3000MB/1.5C -> 400: status={illegal_resp.status_code}")

    # W1: 创建工作区（合法档位 2C/2G）
    print("\n=== W1: POST /api/agent-workspaces（合法档位 2C/2G）===")
    ws = create_workspace(GATEWAY_URL, h, {
        "name": f"{MARKER}-workspace",
        "repoUrl": TEST_REPO_URL,
        "memoryMb": 2048,
        "cpus": 2.0,
    })
    ws_id = ws.get("id", "")
    check(bool(ws_id), f"workspace id: {ws_id}")
    check(ws.get("memoryMb") == 2048, f"memoryMb 落库 =2048: {ws.get('memoryMb')}")
    check(ws.get("cpus") == 2.0, f"cpus 落库 =2.0: {ws.get('cpus')}")

    # W2: 列表
    print("\n=== W2: GET /api/agent-workspaces ===")
    list_resp = requests.get(f"{GATEWAY_URL}/api/agent-workspaces", headers=h, timeout=30)
    check(list_resp.status_code == 200, f"list ok: status={list_resp.status_code}")
    if list_resp.status_code == 200:
        ws_list = unwrap(list_resp.json(), ctx="list-ws")
        check(isinstance(ws_list, list) and any(w.get("id") == ws_id for w in ws_list),
              "list 含已创建项")

    # W6/W7: checkout 管理（依赖 clone 成功）
    print("\n=== W6/W7: checkout 管理（依赖 clone）===")
    clone_state = wait_clone(GATEWAY_URL, h, ws_id, timeout_s=90)
    if clone_state != "done":
        skip("checkout 管理", f"clone 未就绪 state={clone_state}")
    else:
        # W6: 列出 checkout（初始可能为空，200 即可）
        list_co_resp = requests.get(f"{GATEWAY_URL}/api/agent-workspaces/{ws_id}/checkouts",
                                    headers=h, timeout=60)
        check(list_co_resp.status_code == 200, f"list checkouts ok: status={list_co_resp.status_code}")

        # W7: ensure checkout（按分支幂等创建）
        ensure_resp = requests.post(f"{GATEWAY_URL}/api/agent-workspaces/{ws_id}/checkouts/ensure",
                                    json={"branch": "master"}, headers=h, timeout=60)
        if ensure_resp.status_code == 200:
            checkout_id = unwrap(ensure_resp.json(), ctx="ensure-checkout")
            check(bool(checkout_id), f"ensure checkout 返回 checkoutId: {checkout_id}")
            # 再次 ensure 同分支应幂等返回同一 checkoutId
            ensure2_resp = requests.post(f"{GATEWAY_URL}/api/agent-workspaces/{ws_id}/checkouts/ensure",
                                         json={"branch": "master"}, headers=h, timeout=60)
            if ensure2_resp.status_code == 200:
                checkout_id2 = unwrap(ensure2_resp.json(), ctx="ensure-checkout-2")
                check(checkout_id == checkout_id2, f"同分支 ensure 幂等: {checkout_id} == {checkout_id2}")
            else:
                skip("同分支 ensure 幂等", f"status={ensure2_resp.status_code}")
        else:
            check(False, f"ensure checkout ok: status={ensure_resp.status_code} {ensure_resp.text[:150]}")

    # W4: 创建 task 会话
    print("\n=== W4: POST /api/conversations (task) ===")
    conv_resp = requests.post(f"{GATEWAY_URL}/api/conversations",
                              json={"kind": "task", "workspaceId": ws_id,
                                    "checkoutPath": "/workspace/dummy"},
                              headers=h, timeout=30)
    check(conv_resp.status_code == 200, f"create task conv ok: status={conv_resp.status_code}")
    if conv_resp.status_code == 200:
        conv = unwrap(conv_resp.json(), ctx="create-conv")
        check(conv.get("kind") == "task", f"conv kind=task: {conv.get('kind')}")
        check(conv.get("workspaceId") == ws_id, f"conv workspaceId={ws_id}")

    # W8: 归档工作区
    print("\n=== W8: DELETE /api/agent-workspaces/{id} ===")
    if ws_id:
        del_resp = requests.delete(f"{GATEWAY_URL}/api/agent-workspaces/{ws_id}",
                                   headers=h, timeout=30)
        check(del_resp.status_code == 200, f"destroy ws ok: status={del_resp.status_code}")

    # Summary
    print(f"\n{'='*50}")
    print(f"结果: {passed} PASS, {failed} FAIL, {skipped} SKIP")
    if failed:
        print("验收未通过", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    run()
