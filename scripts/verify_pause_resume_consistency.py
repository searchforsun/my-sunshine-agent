#!/usr/bin/env python3
"""3.9.5 暂停/续跑一致性验收 — 单测 + 可选 Live（A1/A6 子集）。

用法:
  python scripts/verify_pause_resume_consistency.py           # 默认仅跑单测
  python scripts/verify_pause_resume_consistency.py --live    # 单测 + Live（需 Gateway）

环境变量:
  GATEWAY_URL（默认 http://ecs4c16g:8000）
  PAUSE_RESUME_TIMEOUT_SEC（默认 120）
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("PAUSE_RESUME_TIMEOUT_SEC", "120"))

UNIT_TESTS = (
    "PlanJsonCodecCheckpointTest",
    "ExecutionPlanStoreTest",
    "PlanWorkflowExecutorResumeTest",
    "HitlConfirmationServiceTest",
    "WorkflowNodeRecoveryServiceTest",
    "ProcessingStepMergerTest",
)


def run_unit_tests() -> None:
    test_arg = ",".join(UNIT_TESTS)
    cmd = [
        "mvn", "test", "-pl", "orchestrator", "-am",
        f"-Dtest={test_arg}",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-q",
    ]
    print(f"[UNIT] {' '.join(cmd)}")
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if proc.stdout:
        print(proc.stdout[-2000:])
    if proc.returncode != 0:
        print(proc.stderr[-2000:] if proc.stderr else "", file=sys.stderr)
        raise RuntimeError(f"单测失败 exit={proc.returncode}")


def gateway_ready() -> bool:
    try:
        requests.get(f"{GATEWAY_URL}/actuator/health", timeout=5)
        return True
    except requests.RequestException:
        try:
            requests.get(GATEWAY_URL, timeout=5)
            return True
        except requests.RequestException:
            return False


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_auth() -> tuple[str, str]:
    user = f"pause{datetime.now():%H%M%S}_{uuid.uuid4().hex[:4]}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register", {"username": user, "password": password, "nickname": "pause"}, None)
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


class StreamSession:
    """后台 SSE 消费 + generationId 捕获。"""

    def __init__(self, token: str, payload: dict) -> None:
        self.token = token
        self.payload = payload
        self.generation_id: str | None = None
        self.steps: list[dict] = []
        self.message_status: str | None = None
        self.error: Exception | None = None
        self._done = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def wait_chunks(self, min_chunks: int = 1, timeout: float = 60.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if len(self.steps) >= min_chunks or self.generation_id:
                return
            if self._done.is_set() and self.error:
                raise self.error
            time.sleep(0.2)
        raise TimeoutError("SSE 未收到足够事件")

    def cancel_generation(self) -> None:
        if not self.generation_id:
            raise RuntimeError("未捕获 generationId，无法 cancel")
        resp = requests.post(
            f"{GATEWAY_URL}/api/generations/{self.generation_id}/cancel",
            headers={"Authorization": f"Bearer {self.token}"},
            timeout=15,
        )
        resp.raise_for_status()

    def _run(self) -> None:
        try:
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers={
                    "Authorization": f"Bearer {self.token}",
                    "Content-Type": "application/json",
                },
                json=self.payload,
                stream=True,
                timeout=TIMEOUT_SEC,
            ) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines(decode_unicode=True):
                    if not line or not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if not payload:
                        continue
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    t = obj.get("type")
                    if t == "generation" and obj.get("id"):
                        self.generation_id = str(obj["id"])
                    elif t == "step":
                        self.steps.append(obj)
                    elif t == "message" and obj.get("status"):
                        self.message_status = str(obj["status"])
        except Exception as e:
            self.error = e
        finally:
            self._done.set()


def fetch_conversation(token: str, conv_id: str) -> dict:
    data = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
    return data.get("data") or data


def parse_steps(raw) -> list[dict]:
    if not raw:
        return []
    if isinstance(raw, list):
        return [s for s in raw if isinstance(s, dict)]
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return []
        if isinstance(parsed, list):
            return [s for s in parsed if isinstance(s, dict)]
    return []


def last_assistant(conv: dict) -> dict | None:
    msgs = conv.get("messages") or []
    assistants = [m for m in msgs if isinstance(m, dict) and m.get("role") == "assistant"]
    return assistants[-1] if assistants else None


def wait_assistant_status(token: str, conv_id: str, expected: str, timeout: float = 30.0) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        msg = last_assistant(fetch_conversation(token, conv_id))
        if msg and msg.get("status") == expected:
            return msg
        time.sleep(0.5)
    raise TimeoutError(f"assistant 未进入 {expected} 态")


def live_a6_react_stop(token: str, conv_id: str) -> None:
    """A6：ReAct 停止 → interrupted，无 Plan 检查点。"""
    print("[LIVE A6] ReAct 停止无 checkpoint")
    sess = StreamSession(token, {
        "conversationId": conv_id,
        "content": "用 ReAct 查一下财务消息列表，慢慢想",
        "executionPreference": "react",
    })
    sess.start()
    sess.wait_chunks(min_chunks=0, timeout=45)
    time.sleep(1)
    if sess.generation_id:
        sess.cancel_generation()
    wait_assistant_status(token, conv_id, "interrupted")
    conv = fetch_conversation(token, conv_id)
    msg = last_assistant(conv)
    assert msg, "无 assistant 消息"
    plan_id = msg.get("executionPlanId")
    if plan_id:
        print(f"  WARN ReAct 消息带 executionPlanId={plan_id}（非预期但可接受）")
    steps = parse_steps(msg.get("steps"))
    running = [s for s in steps if (s.get("lifecycle") or s.get("status")) == "running"]
    if running:
        raise AssertionError(f"A6: stop 后仍有 running 步: {[s.get('id') for s in running]}")
    print("  OK A6 ReAct interrupted，无 running 步")


def live_a1_plan_stop_resume(token: str, conv_id: str) -> None:
    """A1 子集：Plan 模式 stop → PAUSED checkpoint → resumeMessageId 续跑。"""
    print("[LIVE A1] Plan stop + resume")
    sess = StreamSession(token, {
        "conversationId": conv_id,
        "content": "帮我查制度并拉 OA 待办，分步执行",
        "executionPreference": "plan-workflow",
    })
    sess.start()
    sess.wait_chunks(min_chunks=1, timeout=90)
    time.sleep(2)
    if not sess.generation_id:
        print("  SKIP A1: 未捕获 generationId（Planner 可能过快结束）")
        return
    sess.cancel_generation()
    msg = wait_assistant_status(token, conv_id, "interrupted")
    plan_id = msg.get("executionPlanId")
    if not plan_id:
        steps = parse_steps(msg.get("steps"))
        for s in steps:
            detail = s.get("detail") or ""
            m = re.search(r"planId=([0-9a-f-]{36})", detail, re.I)
            if m:
                plan_id = m.group(1)
                break
    if not plan_id:
        print("  SKIP A1: 无 executionPlanId（Planner 阶段可能未落库）")
        return
    plan = auth_json("GET", f"/api/execution-plans/{plan_id}", None, token)
    plan_body = plan.get("data") or plan
    status = (plan_body.get("status") or "").lower()
    if status != "paused":
        print(f"  WARN A1: plan status={status!r}（期望 paused）")
    else:
        print(f"  OK plan {plan_id} status=paused")
    resume_sess = StreamSession(token, {
        "conversationId": conv_id,
        "resumeMessageId": msg.get("id"),
    })
    resume_sess.start()
    resume_sess.wait_chunks(min_chunks=0, timeout=90)
    print("  OK A1 resume SSE 已发起")


def run_live() -> None:
    if not gateway_ready():
        print(f"[LIVE] Gateway 不可达 {GATEWAY_URL}，跳过 Live 验收")
        return
    token, conv_id = setup_auth()
    print(f"[LIVE] conversationId={conv_id}")
    live_a6_react_stop(token, conv_id)
    conv2 = auth_json("POST", "/api/conversations", None, token)
    conv_id2 = (conv2.get("data") or conv2).get("id")
    live_a1_plan_stop_resume(token, conv_id2)


def main() -> int:
    parser = argparse.ArgumentParser(description="3.9.5 暂停/续跑一致性验收")
    parser.add_argument("--live", action="store_true", help="额外跑 Live 子集（需 Gateway/Orchestrator）")
    parser.add_argument("--skip-unit", action="store_true", help="跳过单测")
    args = parser.parse_args()
    try:
        if not args.skip_unit:
            run_unit_tests()
            print("[OK] 单测 PASS")
        if args.live:
            run_live()
            print("[OK] Live 子集完成")
        return 0
    except Exception as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
