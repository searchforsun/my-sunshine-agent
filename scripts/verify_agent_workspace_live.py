#!/usr/bin/env python3
"""Agent Workspace Live 验收 — 工作区 CRUD + conversation kind/workspace 绑定 + 前端端点。

用法:
  python3 scripts/verify_agent_workspace_live.py

断言:
  W1  POST /api/agent-workspaces 创建
  W2  GET /api/agent-workspaces 列表含已创建项
  W3  DELETE /api/agent-workspaces/{id} 归档
  W4  POST /api/conversations kind=task/workspace_id 成功
  W5  GET /api/auth/me 含 gitHub/gitLab 字段
  W6  PATCH /api/auth/profile gitUrl/token 更新后 me 反映

环境变量:
  GATEWAY_URL（默认 http://ecs4c16g:8000）
"""
from __future__ import annotations

import json
import os
import sys
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
MARKER = "ws-e2e-test"


def unwrap(body: dict, *, ctx: str) -> Any:
    if not isinstance(body, dict):
        return body
    code = body.get("code")
    if code is not None and "data" in body:
        if code != 200:
            raise RuntimeError(f"[{ctx}] code={code} msg={body.get('msg')} key={body.get('errorKey')}")
        return body.get("data")
    return body


def api_headers(token: str | None = None) -> dict:
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def login(username: str, password: str) -> tuple[str, dict]:
    resp = requests.post(f"{GATEWAY_URL}/api/auth/login",
                         json={"username": username, "password": password},
                         headers=api_headers())
    assert resp.status_code == 200, f"login failed: {resp.status_code} {resp.text[:200]}"
    body = resp.json()
    data = unwrap(body, ctx="login")
    return data["token"], data


def run():
    passed = 0
    failed = 0

    def check(cond: bool, label: str):
        nonlocal passed, failed
        if cond:
            passed += 1
            print(f"  PASS  {label}")
        else:
            failed += 1
            print(f"  FAIL  {label}")

    # ============================================================
    token, user = login("admin", "admin123")
    user_id = user["userId"]
    tenant_id = user.get("tenantId", "default")
    print(f"登录成功 userId={user_id} tenantId={tenant_id}")

    # W5: /me 含 git 字段
    print("\n=== W5: GET /api/auth/me ===")
    me_resp = requests.get(f"{GATEWAY_URL}/api/auth/me", headers=api_headers(token))
    me = unwrap(me_resp.json(), ctx="me")
    check("githubUrl" in me or "githubTokenSet" in me, "me 含 githubUrl/githubTokenSet")

    # W6: PATCH /api/auth/profile 更新 git
    print("\n=== W6: PATCH /api/auth/profile ===")
    patch_resp = requests.patch(f"{GATEWAY_URL}/api/auth/profile",
                                json={
                                    "nickname": user.get("nickname", "admin"),
                                    "tenantId": tenant_id,
                                    "githubUrl": "https://github.com",
                                    "githubToken": "",
                                    "gitlabUrl": "https://gitlab.example.com",
                                    "gitlabToken": "",
                                },
                                headers=api_headers(token))
    check(patch_resp.status_code == 200, f"profile patch ok: status={patch_resp.status_code}")
    if patch_resp.status_code == 200:
        profile_data = unwrap(patch_resp.json(), ctx="profile")
        check(profile_data.get("githubUrl") == "https://github.com", "profile githubUrl 反映")
        check(profile_data.get("gitlabUrl") == "https://gitlab.example.com", "profile gitlabUrl 反映")
        if profile_data.get("token"):
            token = profile_data["token"]

    # W1: 创建工作区
    print("\n=== W1: POST /api/agent-workspaces ===")
    create_resp = requests.post(f"{GATEWAY_URL}/api/agent-workspaces",
                                json={
                                    "name": f"{MARKER}-workspace",
                                    "repoUrl": "https://github.com/example/demo",
                                    "repoBranch": "main",
                                },
                                headers=api_headers(token))
    check(create_resp.status_code == 200, f"create workspace ok: status={create_resp.status_code}")
    ws = unwrap(create_resp.json(), ctx="create-ws") if create_resp.status_code == 200 else {}
    ws_id = ws.get("id", "") if isinstance(ws, dict) else ""
    check(bool(ws_id), f"workspace id: {ws_id}")

    # W2: 列表工作区
    print("\n=== W2: GET /api/agent-workspaces ===")
    list_resp = requests.get(f"{GATEWAY_URL}/api/agent-workspaces", headers=api_headers(token))
    check(list_resp.status_code == 200, f"list workspaces ok: status={list_resp.status_code}")
    ws_list = unwrap(list_resp.json(), ctx="list-ws") if list_resp.status_code == 200 else []
    check(isinstance(ws_list, list), "list is list")
    if isinstance(ws_list, list) and ws_id:
        check(any(w.get("id") == ws_id for w in ws_list), "list 含已创建项")

    # W4: 创建 task 会话
    print("\n=== W4: POST /api/conversations (task) ===")
    conv_resp = requests.post(f"{GATEWAY_URL}/api/conversations",
                              json={
                                  "kind": "task",
                                  "workspaceId": ws_id,
                                  "checkoutPath": "/workspace/main",
                              },
                              headers=api_headers(token))
    check(conv_resp.status_code == 200, f"create task conv ok: status={conv_resp.status_code}")
    if conv_resp.status_code == 200:
        conv = unwrap(conv_resp.json(), ctx="create-conv")
        check(conv.get("kind") == "task", f"conv kind=task: {conv.get('kind')}")
        check(conv.get("workspaceId") == ws_id, f"conv workspaceId={ws_id}")

    # W3: 归档工作区
    if ws_id:
        print("\n=== W3: DELETE /api/agent-workspaces/{id} ===")
        del_resp = requests.delete(f"{GATEWAY_URL}/api/agent-workspaces/{ws_id}",
                                   headers=api_headers(token))
        check(del_resp.status_code == 200, f"destroy ws ok: status={del_resp.status_code}")

    # Summary
    print(f"\n{'='*50}")
    print(f"结果: {passed} PASS, {failed} FAIL")
    if failed:
        print("验收未通过", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    run()
