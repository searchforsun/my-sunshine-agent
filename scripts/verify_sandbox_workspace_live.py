#!/usr/bin/env python3
"""沙箱 Workspace 抽屉 Live 验收 — 对话级会话复用 + FS list/content。

用法:
  python3 scripts/verify_sandbox_workspace_live.py

断言:
  W1  新对话 status.active=false
  W2  @sandbox-coding-demo 写 /workspace/test.txt 后收到 sandbox_session
  W3  GET workspace 列表含 test.txt；content 含标记
  W4  同会话第二次提问后仍可 list（会话复用，文件仍在）
  W5  无 Agent 沙箱工具步的对话：status inactive；list 懒开箱成功且 entries 为空

环境变量:
  GATEWAY_URL（默认 http://ecs4c16g:8000）
  SANDBOX_WORKSPACE_TIMEOUT_SEC（默认 180）
  SANDBOX_SKILL_ID（默认 sandbox-coding-demo）
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
import uuid
from datetime import datetime
from typing import Any

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("SANDBOX_WORKSPACE_TIMEOUT_SEC", "180"))
SKILL_ID = os.environ.get("SANDBOX_SKILL_ID", "sandbox-coding-demo")
MARKER = f"workspace-drawer-{uuid.uuid4().hex[:8]}"


def unwrap(body: dict, *, ctx: str) -> Any:
    """兼容 R 包装与直出 DTO。"""
    if not isinstance(body, dict):
        return body
    code = body.get("code")
    if code is not None and "data" in body:
        if code != 200:
            raise RuntimeError(f"[{ctx}] code={code} msg={body.get('msg')} key={body.get('errorKey')}")
        return body.get("data")
    return body


def auth_headers(gw: str) -> dict[str, str]:
    user = f"sbws_{datetime.now():%H%M%S}_{uuid.uuid4().hex[:4]}"
    password = "password123"
    reg = requests.post(
        f"{gw}/api/auth/register",
        json={"username": user, "password": password, "nickname": "SandboxWS"},
        timeout=30,
    )
    reg.raise_for_status()
    login = requests.post(
        f"{gw}/api/auth/login",
        json={"username": user, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.text[:200]}")
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }


def create_conversation(gw: str, headers: dict) -> str:
    resp = requests.post(f"{gw}/api/conversations", headers=headers, timeout=30)
    resp.raise_for_status()
    data = unwrap(resp.json(), ctx="createConversation")
    cid = (data or {}).get("id") if isinstance(data, dict) else None
    if not cid:
        raise RuntimeError(f"no conversation id: {resp.text[:200]}")
    return str(cid)


def biz_not_found(resp: requests.Response) -> bool:
    if resp.status_code == 404:
        return True
    try:
        body = resp.json()
    except ValueError:
        return False
    if not isinstance(body, dict):
        return False
    code = body.get("code")
    key = str(body.get("errorKey") or "")
    msg = str(body.get("msg") or "")
    return code == 404 or "sandbox_workspace" in key or "尚无沙箱工作区" in msg


class SseCollector:
    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.confirmation: dict | None = None
        self.sandbox_sessions: list[dict] = []
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE 未在超时内结束")

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
        elif t == "step":
            self.steps.append(obj)
        elif t == "sandbox_session":
            self.sandbox_sessions.append(obj)


def chat_sse(
    gw: str,
    headers: dict,
    conv_id: str,
    query: str,
    *,
    auto_approve: bool = True,
) -> SseCollector:
    collector = SseCollector()
    confirm_called = threading.Event()

    def run() -> None:
        try:
            body = {
                "content": query,
                "conversationId": conv_id,
                "executionPreference": "fast",
            }
            with requests.post(
                f"{gw}/api/chat/stream",
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
                    collector.parse_line(line)
                    if collector.confirmation and auto_approve and not confirm_called.is_set():
                        confirm_called.set()
                        token_val = collector.confirmation.get("confirmationToken")
                        r = requests.post(
                            f"{gw}/api/chat/confirm-tool",
                            headers=headers,
                            json={"token": token_val, "approved": True},
                            timeout=30,
                        )
                        r.raise_for_status()
                        # 可能有多轮 HITL
                        collector.confirmation = None
                        confirm_called.clear()
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run, daemon=True).start()
    collector.wait_done(TIMEOUT_SEC + 30)
    if collector.error and not collector.steps and not collector.sandbox_sessions:
        raise collector.error
    return collector


def workspace_status(gw: str, headers: dict, conv_id: str) -> bool:
    resp = requests.get(
        f"{gw}/api/conversations/{conv_id}/sandbox/workspace/status",
        headers=headers,
        timeout=30,
    )
    if biz_not_found(resp):
        return False
    resp.raise_for_status()
    data = unwrap(resp.json(), ctx="workspaceStatus")
    if isinstance(data, dict):
        return bool(data.get("active"))
    return False


def workspace_list(gw: str, headers: dict, conv_id: str, path: str = "/workspace") -> dict:
    resp = requests.get(
        f"{gw}/api/conversations/{conv_id}/sandbox/workspace",
        headers=headers,
        params={"path": path},
        timeout=30,
    )
    if biz_not_found(resp):
        raise FileNotFoundError("workspace 404")
    resp.raise_for_status()
    data = unwrap(resp.json(), ctx="workspaceList")
    if not isinstance(data, dict):
        raise RuntimeError(f"unexpected list: {data}")
    return data


def workspace_content(gw: str, headers: dict, conv_id: str, path: str) -> dict:
    resp = requests.get(
        f"{gw}/api/conversations/{conv_id}/sandbox/workspace/content",
        headers=headers,
        params={"path": path},
        timeout=30,
    )
    if biz_not_found(resp):
        raise FileNotFoundError("workspace content 404")
    resp.raise_for_status()
    data = unwrap(resp.json(), ctx="workspaceContent")
    if not isinstance(data, dict):
        raise RuntimeError(f"unexpected content: {data}")
    return data


def entry_names(listing: dict) -> list[str]:
    return [str(e.get("name") or "") for e in (listing.get("entries") or [])]


def main() -> int:
    gw = GATEWAY_URL
    print(f"=== Sandbox Workspace Drawer Live ===\ngateway={gw}\nskill={SKILL_ID}\nmarker={MARKER}")
    results: list[tuple[str, str, str]] = []

    def add(gate: str, status: str, detail: str = "") -> None:
        results.append((gate, status, detail))
        tag = {"PASS": "OK", "FAIL": "FAIL", "SKIP": "SKIP"}.get(status, status)
        print(f"[{tag}] {gate}: {detail}" if detail else f"[{tag}] {gate}")

    try:
        headers = auth_headers(gw)
    except Exception as exc:
        print(f"[FAIL] auth: {exc}")
        return 1

    # W1
    try:
        conv = create_conversation(gw, headers)
        active = workspace_status(gw, headers, conv)
        if active:
            add("W1", "FAIL", "新对话不应有 active workspace")
        else:
            add("W1", "PASS", f"conv={conv} inactive")
    except Exception as exc:
        add("W1", "FAIL", str(exc))
        return 1

    # W2 + write
    try:
        coll = chat_sse(
            gw,
            headers,
            conv,
            (
                f"@{SKILL_ID} 请只用 sandbox__write 把以下内容写入 /workspace/test.txt，"
                f"不要用 exec：\n{MARKER}\n写完后简短确认。"
            ),
            auto_approve=True,
        )
        tool_ids = [
            str(s.get("id") or "")
            for s in coll.steps
            if "sandbox__" in str(s.get("id") or "")
        ]
        has_session = bool(coll.sandbox_sessions)
        # status API 也可作为会话就绪证据
        active_after = workspace_status(gw, headers, conv)
        if not active_after:
            add(
                "W2",
                "FAIL",
                f"status inactive；sse={len(coll.sandbox_sessions)} tools={tool_ids[:8]}",
            )
        elif not has_session:
            add(
                "W2",
                "FAIL",
                f"status active 但未收到 SSE sandbox_session；tools={tool_ids[:8]}",
            )
        else:
            add(
                "W2",
                "PASS",
                f"active={active_after} sse_sessions={len(coll.sandbox_sessions)} tools={tool_ids[:6]}",
            )
    except Exception as exc:
        add("W2", "FAIL", str(exc))
        return 1

    # W3 list + content
    try:
        listing = workspace_list(gw, headers, conv)
        names = entry_names(listing)
        if "test.txt" not in names:
            add("W3", "FAIL", f"列表无 test.txt: {names}")
        else:
            content = workspace_content(gw, headers, conv, "/workspace/test.txt")
            body = content.get("content") or ""
            if MARKER not in body:
                add("W3", "FAIL", f"content 缺 marker: {body[:120]!r}")
            else:
                skills = workspace_list(gw, headers, conv, "/skills")
                skill_names = entry_names(skills)
                if SKILL_ID not in skill_names:
                    add("W3", "FAIL", f"/skills 无 {SKILL_ID}: {skill_names}")
                else:
                    add("W3", "PASS", f"list={names}; skills={skill_names}; content has marker")
    except Exception as exc:
        add("W3", "FAIL", str(exc))

    # W4 reuse
    try:
        coll2 = chat_sse(
            gw,
            headers,
            conv,
            f"@{SKILL_ID} 用 sandbox__read 读 /workspace/test.txt，不要改文件，简短回复内容要点。",
            auto_approve=True,
        )
        listing2 = workspace_list(gw, headers, conv)
        names2 = entry_names(listing2)
        if "test.txt" not in names2:
            add("W4", "FAIL", f"复用后丢失 test.txt: {names2}; steps={len(coll2.steps)}")
        else:
            content2 = workspace_content(gw, headers, conv, "/workspace/test.txt")
            if MARKER not in (content2.get("content") or ""):
                add("W4", "FAIL", "复用后内容丢失")
            else:
                add("W4", "PASS", "同会话二次提问后文件仍在")
    except Exception as exc:
        add("W4", "FAIL", str(exc))

    # W5 无 Agent 沙箱工具步：list 懒 ensure，空目录
    try:
        conv2 = create_conversation(gw, headers)
        active_before = workspace_status(gw, headers, conv2)
        listing = workspace_list(gw, headers, conv2)
        names = entry_names(listing)
        if active_before:
            add("W5", "FAIL", f"新对话不应 active: conv={conv2}")
        elif names:
            add("W5", "FAIL", f"无工具步对话 list 应为空: {names}")
        else:
            add("W5", "PASS", "status inactive；list 成功 entries=[]")
    except Exception as exc:
        add("W5", "FAIL", str(exc))

    print("\n=== Report ===")
    print(json.dumps({g: {"status": s, "detail": d} for g, s, d in results}, ensure_ascii=False, indent=2))
    fails = [r for r in results if r[1] == "FAIL"]
    if fails:
        print(f"[FAIL] {len(fails)} gate(s)")
        return 1
    print("[PASS] Sandbox Workspace Drawer Live")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
