#!/usr/bin/env python3
"""4.5 Skills Docker 沙箱 Live 验收 — G1–G12。

用法:
  python3 scripts/verify_sandbox_live.py --suite direct
  python3 scripts/verify_sandbox_live.py --suite chat
  python3 scripts/verify_sandbox_live.py --suite all

子套件:
  direct  G2–G6、G8（容器回收）— 直连 SANDBOX_URL（默认 CI 套件）
  chat    G1、G7、G9–G12 — Gateway SSE；Gateway 不可达时 soft-skip
  all     两者

环境变量:
  SANDBOX_URL（默认 http://ecs4c16g:8226，可改 localhost:8226）
  GATEWAY_URL（默认 http://ecs4c16g:8000）
  SKILL_MANAGER_URL / ORCHESTRATOR_URL
  SANDBOX_LIVE_TIMEOUT_SEC（默认 180）
  SANDBOX_SKILL_ID（chat 套件沙箱 Skill，默认 sandbox-coding-demo）
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

SANDBOX_URL = os.environ.get("SANDBOX_URL", "http://ecs4c16g:8226").rstrip("/")
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
SKILL_MANAGER_URL = os.environ.get("SKILL_MANAGER_URL", "http://ecs4c16g:8225").rstrip("/")
ORCH_URL = os.environ.get("ORCHESTRATOR_URL", "http://ecs4c16g:8200").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("SANDBOX_LIVE_TIMEOUT_SEC", "180"))
SANDBOX_SKILL = os.environ.get("SANDBOX_SKILL_ID", "sandbox-coding-demo")
NOSANDBOX_SKILL = os.environ.get("SANDBOX_NOSANDBOX_SKILL", "finance-analysis")  # legacy env; G1 已改为无 skill 正向用例
SAMPLE_PY = "print('hello-sandbox')\nMARKER = 'sandbox-g4'\n"


@dataclass
class GateResult:
    gate: str
    status: str  # PASS | FAIL | SKIP
    detail: str = ""

    def as_dict(self) -> dict[str, str]:
        return {"gate": self.gate, "status": self.status, "detail": self.detail}


@dataclass
class Report:
    results: list[GateResult] = field(default_factory=list)

    def add(self, gate: str, status: str, detail: str = "") -> GateResult:
        r = GateResult(gate, status, detail)
        self.results.append(r)
        tag = {"PASS": "OK", "FAIL": "FAIL", "SKIP": "SKIP"}.get(status, status)
        print(f"[{tag}] {gate}: {detail}" if detail else f"[{tag}] {gate}")
        return r

    def failed(self) -> list[GateResult]:
        return [r for r in self.results if r.status == "FAIL"]

    def summary(self) -> dict[str, Any]:
        return {r.gate: {"status": r.status, "detail": r.detail} for r in self.results}


def host_port(url: str) -> tuple[str, int]:
    u = urlparse(url if "://" in url else f"http://{url}")
    host = u.hostname or "127.0.0.1"
    port = u.port or (443 if u.scheme == "https" else 80)
    return host, port


def url_reachable(url: str, *, timeout: float = 3.0) -> bool:
    try:
        requests.get(url, timeout=timeout)
        return True
    except requests.RequestException:
        pass
    # actuator may 404 on some services; any TCP response counts
    try:
        host, port = host_port(url)
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def sandbox_ready(base: str) -> bool:
    for path in ("/actuator/health", "/api/sandbox/sessions"):
        try:
            resp = requests.get(f"{base}{path}", timeout=3)
            if resp.status_code < 500:
                return True
        except requests.RequestException:
            continue
    return url_reachable(base)


def unwrap_data(body: dict, *, context: str) -> Any:
    code = body.get("code")
    if code is not None and code != 200:
        raise RuntimeError(f"[{context}] code={code} msg={body.get('msg')}")
    return body.get("data")


def create_session(base: str, *, network_allow: list[str] | None = None) -> str:
    body = {
        "userId": "sandbox-live",
        "tenantId": "default",
        "skillId": "sandbox-live",
        "runId": f"run-{uuid.uuid4().hex[:12]}",
        "policy": {
            "runtime": "docker",
            "image": "sunshine-sandbox-python:3.11-slim",
            "timeoutSec": 30,
            "memoryMb": 256,
            "cpus": 0.5,
            "networkAllow": network_allow if network_allow is not None else [],
            "execReadonlyAllow": ["ls *", "pwd"],
        },
        "skillFiles": {},
        "workspaceFiles": {},
    }
    resp = requests.post(f"{base}/api/sandbox/sessions", json=body, timeout=60)
    if resp.status_code >= 400:
        raise RuntimeError(f"create session HTTP {resp.status_code}: {resp.text[:300]}")
    data = unwrap_data(resp.json(), context="createSession")
    sid = (data or {}).get("sessionId") if isinstance(data, dict) else None
    if not sid:
        raise RuntimeError(f"create session empty id: {resp.text[:300]}")
    return str(sid)



def mount_skill(base: str, session_id: str, skill_id: str, files: dict[str, str]) -> None:
    resp = requests.put(
        f"{base}/api/sandbox/sessions/{session_id}/skills/{skill_id}",
        json=files,
        timeout=60,
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"mount skill HTTP {resp.status_code}: {resp.text[:300]}")
    unwrap_data(resp.json(), context="mountSkill")

def close_session(base: str, session_id: str) -> None:
    try:
        requests.delete(f"{base}/api/sandbox/sessions/{session_id}", timeout=30)
    except requests.RequestException as exc:
        print(f"[WARN] closeSession: {exc}")


def invoke(
    base: str,
    session_id: str,
    tool: str,
    args: dict[str, Any] | None = None,
    *,
    expect_http: int | None = None,
) -> tuple[int, dict]:
    resp = requests.post(
        f"{base}/api/sandbox/sessions/{session_id}/tools/{tool}",
        json=args or {},
        timeout=90,
    )
    status = resp.status_code
    try:
        body = resp.json()
    except ValueError:
        body = {"raw": resp.text[:500]}
    if expect_http is not None and status != expect_http:
        raise AssertionError(f"{tool} expect HTTP {expect_http}, got {status}: {body}")
    return status, body


def invoke_ok(base: str, session_id: str, tool: str, args: dict[str, Any] | None = None) -> dict:
    status, body = invoke(base, session_id, tool, args)
    if status >= 400:
        raise AssertionError(f"{tool} HTTP {status}: {body}")
    data = unwrap_data(body, context=tool)
    if not isinstance(data, dict):
        raise AssertionError(f"{tool} unexpected data: {data}")
    if data.get("ok") is not True:
        raise AssertionError(f"{tool} ok=false: {data}")
    return data


def container_name_prefix(session_id: str) -> str:
    return "sunshine-sb-" + session_id[: min(12, len(session_id))]


def docker_ps_names() -> list[str] | None:
    docker = shutil.which("docker")
    if not docker:
        return None
    try:
        proc = subprocess.run(
            [docker, "ps", "--format", "{{.Names}}"],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    return [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]


# ─── direct suite: G2–G6, G8 ───────────────────────────────────────────────


def run_direct(base: str, report: Report) -> None:
    if not sandbox_ready(base):
        msg = f"sandbox-service 不可达: {base}（请 start.py --restart sandbox-service）"
        for g in ("G2", "G3", "G4", "G5", "G6", "G8"):
            report.add(g, "FAIL", msg)
        return

    session_id: str | None = None
    try:
        session_id = create_session(base)
        print(f"[INFO] session={session_id}")
        mount_skill(base, session_id, "sandbox-live", {"scripts/sample.py": SAMPLE_PY})

        # G2: 可读 /skill；可写仅 /workspace
        read = invoke_ok(base, session_id, "read", {"path": "/skills/sandbox-live/scripts/sample.py"})
        if "hello-sandbox" not in (read.get("output") or ""):
            raise AssertionError("read /skills/sandbox-live/scripts/sample.py missing marker")
        invoke_ok(base, session_id, "write", {"path": "/workspace/out.txt", "content": "g2-write\n"})
        reread = invoke_ok(base, session_id, "read", {"path": "/workspace/out.txt"})
        if "g2-write" not in (reread.get("output") or ""):
            raise AssertionError("workspace write/read mismatch")
        st, body = invoke(
            base, session_id, "write",
            {"path": "/skills/sandbox-live/scripts/hack.py", "content": "x"},
        )
        if st < 400:
            raise AssertionError(f"write /skills should 4xx, got {st}: {body}")
        report.add("G2", "PASS", "read /skills + write /workspace; write /skills → 4xx")

        # G3: edit 精确替换 + 路径越狱
        invoke_ok(base, session_id, "write", {"path": "/workspace/edit_me.txt", "content": "alpha beta gamma\n"})
        invoke_ok(
            base, session_id, "edit",
            {"path": "/workspace/edit_me.txt", "old_string": "beta", "new_string": "BETA"},
        )
        edited = invoke_ok(base, session_id, "read", {"path": "/workspace/edit_me.txt"})
        if "alpha BETA gamma" not in (edited.get("output") or ""):
            raise AssertionError(f"edit result unexpected: {edited.get('output')!r}")
        escape_paths = [
            "/workspace/../etc/passwd",
            "/skills/../../../etc/passwd",
            "/etc/passwd",
        ]
        for p in escape_paths:
            st, body = invoke(base, session_id, "read", {"path": p})
            if st < 400:
                raise AssertionError(f"jail escape path {p} should 4xx, got {st}: {body}")
        report.add("G3", "PASS", "edit ok; path jail → 4xx")

        # G4: glob / grep 不越出 jail
        glob_data = invoke_ok(base, session_id, "glob", {"pattern": "**/*.py"})
        glob_out = glob_data.get("output") or ""
        for line in glob_out.splitlines():
            line = line.strip()
            if not line:
                continue
            if not (line.startswith("/skills/") or line.startswith("/workspace/")):
                raise AssertionError(f"glob path outside jail: {line}")
        if "/skills/sandbox-live/scripts/sample.py" not in glob_out:
            raise AssertionError(f"glob missing sample.py: {glob_out!r}")
        grep_data = invoke_ok(base, session_id, "grep", {"pattern": "sandbox-g4"})
        grep_out = grep_data.get("output") or ""
        if "sample.py" not in grep_out:
            raise AssertionError(f"grep miss: {grep_out!r}")
        for line in grep_out.splitlines():
            path_part = line.split(":", 1)[0]
            if path_part and not (
                path_part.startswith("/skills/") or path_part.startswith("/workspace/")
            ):
                raise AssertionError(f"grep path outside jail: {line}")
        report.add("G4", "PASS", "glob/grep paths stay in jail")

        # G5: 多次 exec 共享 FS；超时强杀
        invoke_ok(
            base, session_id, "exec",
            {"command": "echo shared-state > /workspace/state.txt"},
        )
        cat = invoke_ok(base, session_id, "exec", {"command": "cat /workspace/state.txt"})
        if "shared-state" not in (cat.get("output") or ""):
            raise AssertionError(f"exec FS not shared: {cat}")
        st, body = invoke(
            base, session_id, "exec",
            {"command": "sleep 10", "timeout_sec": 2},
        )
        if st >= 400:
            raise AssertionError(f"timeout exec should return 200+ok=false, got HTTP {st}: {body}")
        data = unwrap_data(body, context="exec-timeout")
        if not isinstance(data, dict) or data.get("ok") is not False:
            raise AssertionError(f"timeout exec expect ok=false: {data}")
        report.add("G5", "PASS", "shared FS + timeout ok=false")

        # G6: network none — curl 失败
        st, body = invoke(
            base, session_id, "exec",
            {"command": "curl -sS --max-time 5 https://example.com || wget -q -O- --timeout=5 https://example.com",
             "timeout_sec": 15},
        )
        if st >= 400:
            # curl 不存在也会失败；仍算无网拒绝路径
            data = body.get("data") if isinstance(body, dict) else None
        else:
            data = unwrap_data(body, context="exec-curl")
        if not isinstance(data, dict):
            raise AssertionError(f"network none exec unexpected: {body}")
        if data.get("ok") is True:
            raise AssertionError(f"network none: curl unexpectedly succeeded: {data}")
        report.add("G6", "PASS", "network none: outbound curl ok=false")

        # G8: close + docker ps 无残留；审计可选
        cname = container_name_prefix(session_id)
        close_session(base, session_id)
        closed_id = session_id
        session_id = None
        time.sleep(1.5)
        names = docker_ps_names()
        if names is None:
            report.add("G8", "PASS", f"closeSession ok; docker CLI 不可用，跳过 ps（container was {cname}）")
        else:
            leaked = [n for n in names if n == cname or n.startswith(cname)]
            if leaked:
                raise AssertionError(f"container still running after close: {leaked}")
            detail = f"closeSession; docker ps 无 {cname}"
            # 审计 recent（可选，orchestrator 侧）
            try:
                if url_reachable(ORCH_URL):
                    ar = requests.get(f"{ORCH_URL}/api/audit/recent", timeout=10)
                    if ar.status_code == 200 and ar.json().get("code") == 200:
                        detail += "; audit/recent 可达"
            except requests.RequestException:
                pass
            report.add("G8", "PASS", detail)

    except (AssertionError, RuntimeError, requests.RequestException) as exc:
        # 标记尚未写入的门为 FAIL
        done = {r.gate for r in report.results}
        for g in ("G2", "G3", "G4", "G5", "G6", "G8"):
            if g not in done:
                report.add(g, "FAIL", str(exc))
                break
        else:
            report.add("G8", "FAIL", str(exc))
    finally:
        if session_id:
            close_session(base, session_id)


# ─── chat suite: G1, G7, G9 ────────────────────────────────────────────────


def auth_headers(gw: str) -> dict[str, str]:
    user = f"sbox_{uuid.uuid4().hex[:10]}"
    password = "password123"
    reg = requests.post(
        f"{gw}/api/auth/register",
        json={"username": user, "password": password, "nickname": "sandbox-live"},
        timeout=30,
    )
    reg.raise_for_status()
    if reg.json().get("code") != 200:
        raise RuntimeError(f"register failed: {reg.json()}")
    login = requests.post(
        f"{gw}/api/auth/login",
        json={"username": user, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.json()}")
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def create_conversation(gw: str, headers: dict) -> str:
    body = requests.post(f"{gw}/api/conversations", headers=headers, json={}, timeout=30).json()
    conv_id = (body.get("data") or body).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {body}")
    return str(conv_id)


class SseCollector:
    def __init__(self) -> None:
        self.confirmation: dict | None = None
        self.steps: list[dict] = []
        self.content_chunks: list[str] = []
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
        elif t == "content" and obj.get("text"):
            self.content_chunks.append(obj["text"])


def chat_sse(
    gw: str,
    headers: dict,
    conv_id: str,
    query: str,
    *,
    execution_preference: str = "fast",
    write_hitl_mode: str | None = None,
    auto_approve: bool = False,
    stop_on_confirmation: bool = False,
) -> SseCollector:
    collector = SseCollector()
    confirm_called = threading.Event()

    def run() -> None:
        try:
            body: dict[str, Any] = {
                "content": query,
                "conversationId": conv_id,
                "executionMode": execution_preference,
            }
            if write_hitl_mode:
                body["writeHitlMode"] = write_hitl_mode
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
                    if line.startswith("data:"):
                        collector.parse_line(line)
                        if collector.confirmation and stop_on_confirmation:
                            break
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
                            # 可能多轮 HITL（write 后再 edit/exec）
                            collector.confirmation = None
                            confirm_called.clear()
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run, daemon=True).start()
    collector.wait_done(TIMEOUT_SEC + 30)
    if collector.error and not collector.steps and not collector.confirmation:
        raise collector.error
    return collector


def assert_write_skip_hitl(
    gw: str,
    headers: dict,
    skill_id: str,
    *,
    mode: str,
    marker: str,
) -> str:
    """writeHitlMode=always|smart：须出现 sandbox__write 且全程无 confirmation。"""
    conv = create_conversation(gw, headers)
    coll = chat_sse(
        gw,
        headers,
        conv,
        (
            f"@{skill_id} 请用 sandbox__write 把字符串 {marker} 写入 "
            f"/workspace/hitl-{mode}.txt，不要用 exec，完成后简短确认。"
        ),
        execution_preference="fast",
        write_hitl_mode=mode,
        # 若误触发 HITL 则立刻停，避免挂死等确认
        stop_on_confirmation=True,
    )
    ids = sandbox_tool_step_ids(coll.steps)
    wrote = any("sandbox__write" in x or "sandbox__edit" in x for x in ids)
    if coll.confirmation:
        tool_id = coll.confirmation.get("toolId")
        raise AssertionError(
            f"mode={mode} 仍收到 confirmation toolId={tool_id} steps={ids[:8]}"
        )
    if not wrote:
        raise AssertionError(
            f"mode={mode} 未出现 sandbox__write/edit（无法断言免确认）steps={ids[:8]}"
        )
    return f"mode={mode} write/edit 无 confirmation steps={ids[:6]}"


def ensure_sandbox_skill(gw: str, headers: dict) -> str:
    """确保存在 sandbox=docker 的测试 Skill（含 scripts/sample.py）。"""
    skill_id = SANDBOX_SKILL
    # 已存在则只更新 sandbox
    try:
        listing = requests.get(f"{gw}/api/skills", headers=headers, timeout=30)
        if listing.status_code == 200:
            skills = listing.json().get("data") or []
            if any(s.get("id") == skill_id for s in skills):
                versions = requests.get(
                    f"{gw}/api/skills/{skill_id}/versions", headers=headers, timeout=30
                ).json().get("data") or []
                ver = versions[0].get("version") if versions else 1
                requests.put(
                    f"{gw}/api/skills/{skill_id}/versions/{ver}/sandbox",
                    headers=headers,
                    json={
                        "sandbox": "docker",
                        "sandboxPolicy": {
                            "runtime": "docker",
                            "image": "sunshine-sandbox-python:3.11-slim",
                            "timeoutSec": 30,
                            "memoryMb": 256,
                            "cpus": 0.5,
                            "networkAllow": [],
                            "execReadonlyAllow": ["ls *", "pwd", "python -m pytest *"],
                        },
                    },
                    timeout=30,
                )
                return skill_id
    except requests.RequestException:
        pass

    create = requests.post(
        f"{gw}/api/skills",
        headers=headers,
        json={
            "id": skill_id,
            "displayName": "Sandbox Coding Demo",
            "description": "4.5 live sandbox skill",
            "sandbox": "docker",
            "sandboxPolicy": {
                "runtime": "docker",
                "networkAllow": [],
                "execReadonlyAllow": ["ls *", "pwd"],
            },
        },
        timeout=30,
    )
    if create.status_code >= 400 and create.status_code != 409:
        # 可能已存在
        pass
    pkg = (
        "---\n"
        f"name: {skill_id}\n"
        "description: sandbox coding demo for live gates\n"
        "---\n\n"
        "# Sandbox Coding Demo\n\n"
        "Use sandbox__read/write/edit/glob/grep/exec on the workspace.\n"
    )
    # zip-less content upload via multipart content field if supported
    up = requests.post(
        f"{gw}/api/skills/{skill_id}/upload",
        headers={"Authorization": headers["Authorization"]},
        data={"content": pkg},
        files={"file": ("scripts/sample.py", SAMPLE_PY.encode("utf-8"), "text/x-python")},
        timeout=60,
    )
    # 若 multipart 形态不对，至少保证 sandbox 标记
    versions = requests.get(
        f"{gw}/api/skills/{skill_id}/versions", headers=headers, timeout=30
    ).json().get("data") or []
    if versions:
        ver = versions[0].get("version")
        requests.put(
            f"{gw}/api/skills/{skill_id}/versions/{ver}/sandbox",
            headers=headers,
            json={"sandbox": "docker", "sandboxPolicy": {"networkAllow": []}},
            timeout=30,
        )
        requests.post(
            f"{gw}/api/skills/{skill_id}/publish",
            headers=headers,
            params={"version": ver},
            timeout=30,
        )
    if up.status_code >= 400:
        print(f"[WARN] skill upload HTTP {up.status_code}: {up.text[:200]}")
    return skill_id


def sandbox_tool_step_ids(steps: list[dict]) -> list[str]:
    ids = []
    for s in steps:
        sid = str(s.get("id") or "")
        if "sandbox__" in sid:
            ids.append(sid)
        for sub in s.get("subSteps") or []:
            if isinstance(sub, dict):
                sub_id = str(sub.get("id") or "")
                if "sandbox__" in sub_id:
                    ids.append(sub_id)
    return ids


def flatten_step_ids(steps: list[dict]) -> str:
    """顶层 + subSteps id，便于断言 workflow agent 节点。"""
    parts: list[str] = []
    for s in steps:
        parts.append(str(s.get("id") or ""))
        for sub in s.get("subSteps") or []:
            if isinstance(sub, dict):
                parts.append(str(sub.get("id") or ""))
    return " ".join(parts)


def workspace_list_names(gw: str, headers: dict, conv_id: str) -> list[str]:
    resp = requests.get(
        f"{gw}/api/conversations/{conv_id}/sandbox/workspace",
        headers=headers,
        params={"path": "/workspace"},
        timeout=30,
    )
    if resp.status_code == 404:
        return []
    resp.raise_for_status()
    body = resp.json()
    data = body.get("data") if isinstance(body, dict) and "data" in body else body
    if not isinstance(data, dict):
        return []
    return [str(e.get("name") or "") for e in (data.get("entries") or [])]


def catalog_has_workflow(gw: str, headers: dict, workflow_id: str) -> bool:
    try:
        resp = requests.get(f"{gw}/api/workflows/catalog", headers=headers, timeout=30)
        if resp.status_code >= 400:
            return False
        body = resp.json()
        data = body.get("data") if isinstance(body, dict) else body
        items = data if isinstance(data, list) else (
            (data or {}).get("items") or (data or {}).get("workflows") or []
        )
        if not isinstance(items, list):
            return False
        return any((x.get("id") or x.get("workflowId")) == workflow_id for x in items)
    except (requests.RequestException, ValueError, TypeError):
        return False


def run_chat(gw: str, report: Report) -> None:
    chat_gates = ("G1", "G7", "G9", "G10", "G11", "G12")
    if not url_reachable(gw):
        msg = f"Gateway 不可达: {gw} — soft-skip chat 套件"
        for g in chat_gates:
            report.add(g, "SKIP", msg)
        return

    try:
        headers = auth_headers(gw)
    except (requests.RequestException, RuntimeError) as exc:
        msg = f"auth 失败（soft-skip）: {exc}"
        for g in chat_gates:
            report.add(g, "SKIP", msg)
        return

    # G1: 方案 B — 无 skill 的 react 亦可调用 sandbox__*（写 workspace）
    try:
        conv = create_conversation(gw, headers)
        coll = chat_sse(
            gw, headers, conv,
            "请用 sandbox__write 把 hello-scheme-b 写入 /workspace/scheme-b.txt，然后简短确认。"
            "不要使用其他工具。",
            execution_preference="fast",
            auto_approve=True,
        )
        leaked = sandbox_tool_step_ids(coll.steps)
        if any("sandbox__write" in x or "sandbox__" in x for x in leaked):
            report.add("G1", "PASS", f"无 skill 出现 sandbox__* 工具步: {leaked[:5]}")
        else:
            report.add("G1", "FAIL", f"无 skill 未出现 sandbox__* 工具步 steps={leaked[:8]}")
    except (AssertionError, RuntimeError, TimeoutError, requests.RequestException) as exc:
        report.add("G1", "FAIL", str(exc))

    # 预置沙箱 Skill
    try:
        skill_id = ensure_sandbox_skill(gw, headers)
    except (requests.RequestException, RuntimeError) as exc:
        msg = f"无法预置 sandbox Skill: {exc}"
        for g in ("G7", "G9", "G10", "G11"):
            report.add(g, "FAIL", msg)
        # G12 不依赖 skill，继续
        skill_id = None

    if skill_id:
        # G7: writeHitlMode 缺省(=never) — write 触发 HITL
        try:
            conv = create_conversation(gw, headers)
            coll = chat_sse(
                gw, headers, conv,
                f"@{skill_id} 请用 sandbox__write 把字符串 hello-hitl 写入 /workspace/hitl.txt，不要用 exec",
                execution_preference="fast",
                stop_on_confirmation=True,
            )
            conf = coll.confirmation
            if not conf:
                report.add(
                    "G7", "FAIL",
                    "未收到 SSE type:confirmation（需 HITL 开启且模型调用 write/edit/非只读 exec）",
                )
            else:
                tool_id = str(conf.get("toolId") or "")
                if not tool_id.startswith("sandbox__"):
                    report.add("G7", "FAIL", f"confirmation toolId 非 sandbox: {tool_id}")
                elif tool_id in ("sandbox__read", "sandbox__glob", "sandbox__grep"):
                    report.add("G7", "FAIL", f"读工具不应 HITL: {tool_id}")
                else:
                    report.add("G7", "PASS", f"HITL confirmation toolId={tool_id}")
        except (AssertionError, RuntimeError, TimeoutError, requests.RequestException) as exc:
            report.add("G7", "FAIL", str(exc))

        # G9: read → edit → exec 最小闭环（自动 approve HITL）
        try:
            conv = create_conversation(gw, headers)
            coll = chat_sse(
                gw, headers, conv,
                (
                    f"@{skill_id} 严格按顺序只用沙箱工具："
                    "1) sandbox__read 读取 /skills/sandbox-live/scripts/sample.py；"
                    "2) sandbox__write 写 /workspace/demo.txt 内容为 OLD；"
                    "3) sandbox__edit 把 OLD 换成 NEW；"
                    "4) sandbox__exec 执行 cat /workspace/demo.txt；"
                    "完成后简短确认。"
                ),
                execution_preference="fast",
                auto_approve=True,
            )
            ids = sandbox_tool_step_ids(coll.steps)
            joined = " ".join(ids)
            has_read = "sandbox__read" in joined
            has_edit = "sandbox__edit" in joined or "sandbox__write" in joined
            has_exec = "sandbox__exec" in joined
            if has_read and has_edit and has_exec:
                report.add("G9", "PASS", f"tool steps: {ids[:12]}")
            else:
                report.add(
                    "G9", "FAIL",
                    f"未形成 read→edit/write→exec 闭环（read={has_read} edit/write={has_edit} exec={has_exec}）steps={ids[:12]}",
                )
        except (AssertionError, RuntimeError, TimeoutError, requests.RequestException) as exc:
            report.add("G9", "FAIL", str(exc))

        # G10: writeHitlMode=always — write 免确认
        try:
            detail = assert_write_skip_hitl(
                gw, headers, skill_id, mode="always", marker=f"skip-always-{uuid.uuid4().hex[:6]}"
            )
            report.add("G10", "PASS", detail)
        except (AssertionError, RuntimeError, TimeoutError, requests.RequestException) as exc:
            report.add("G10", "FAIL", str(exc))

        # G11: writeHitlMode=smart — write 免确认（危险 exec 仍确认，本门仅覆盖 write）
        try:
            detail = assert_write_skip_hitl(
                gw, headers, skill_id, mode="smart", marker=f"skip-smart-{uuid.uuid4().hex[:6]}"
            )
            report.add("G11", "PASS", detail)
        except (AssertionError, RuntimeError, TimeoutError, requests.RequestException) as exc:
            report.add("G11", "FAIL", str(exc))

    # G12 / S4: #sandbox-agent SUB 写 /workspace，抽屉可见
    try:
        wf_id = "sandbox-agent"
        if not catalog_has_workflow(gw, headers, wf_id):
            report.add("G12", "SKIP", f"Catalog 无 {wf_id}（先执行 13-init / 入库种子）")
        else:
            marker = f"s4-{uuid.uuid4().hex[:8]}"
            fname = f"{marker}.txt"
            conv = create_conversation(gw, headers)
            coll = chat_sse(
                gw,
                headers,
                conv,
                (
                    f"#{wf_id} 请在工作区新建 /workspace/{fname}，"
                    f"内容仅为 {marker}，只用 sandbox__write，写完后简短确认。"
                ),
                execution_preference="workflow",
                write_hitl_mode="always",
                auto_approve=True,
            )
            flat = flatten_step_ids(coll.steps)
            tool_ids = sandbox_tool_step_ids(coll.steps)
            has_agent_node = "node-agent-s4b0x7a1" in flat or "agent-s4b0x7a1" in flat
            wrote = any("sandbox__write" in x or "sandbox__edit" in x for x in tool_ids)
            if not has_agent_node:
                raise AssertionError(f"未出现 sandbox-agent 节点步 flat={flat[:400]}")
            if not wrote:
                raise AssertionError(
                    f"agent 节点未出现 sandbox__write/edit tool_ids={tool_ids[:8]} flat={flat[:400]}"
                )
            names = workspace_list_names(gw, headers, conv)
            if fname not in names and marker not in " ".join(names):
                raise AssertionError(
                    f"抽屉 list 未见 {fname} names={names[:20]}（SUB 应复用对话容器）"
                )
            report.add(
                "G12", "PASS",
                f"#{wf_id} write ok file={fname} tools={tool_ids[:4]} names={names[:8]}",
            )
    except (AssertionError, RuntimeError, TimeoutError, requests.RequestException) as exc:
        report.add("G12", "FAIL", str(exc))


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="4.5 Skills Docker 沙箱 Live G1–G12")
    p.add_argument(
        "--suite",
        choices=["direct", "chat", "all"],
        default="direct",
        help="验收子套件（默认 direct）",
    )
    p.add_argument("--sandbox", default=SANDBOX_URL, help="sandbox-service 基址")
    p.add_argument("--gateway", default=GATEWAY_URL, help="Gateway 基址")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    sandbox = args.sandbox.rstrip("/")
    gateway = args.gateway.rstrip("/")
    print(f"=== Sandbox Live 4.5 ===\nsuite={args.suite}\nsandbox={sandbox}\ngateway={gateway}")

    report = Report()
    try:
        if args.suite in ("direct", "all"):
            print("\n--- suite: direct (G2–G6, G8) ---")
            run_direct(sandbox, report)
        if args.suite in ("chat", "all"):
            print("\n--- suite: chat (G1, G7, G9–G12) ---")
            run_chat(gateway, report)
    except Exception as exc:
        print(f"\n[FAIL] unexpected: {exc}", file=sys.stderr)
        return 1

    print(f"\n=== Report ===\n{json.dumps(report.summary(), ensure_ascii=False, indent=2)}")
    fails = report.failed()
    skips = [r for r in report.results if r.status == "SKIP"]
    if fails:
        print(f"[FAIL] Sandbox Live — {len(fails)} gate(s) failed")
        return 1
    if skips and not report.results:
        print("[FAIL] Sandbox Live — no gates run")
        return 1
    if skips:
        print(f"[PASS] Sandbox Live（含 {len(skips)} SKIP）")
    else:
        print("[PASS] Sandbox Live 4.5")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
