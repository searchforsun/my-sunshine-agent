#!/usr/bin/env python3
"""Planner-Executor 内核冒烟（非完整 H-7）。

验证：`agent.execution.harness.enabled=true` 时，Chat `executionMode=pro`
走 PlannerHarnessExecutor，并出现 PlanNotebook Redis 键或 harness 日志。

用法:
  python3 scripts/verify_planner_harness_kernel_smoke.py
  GATEWAY_URL=http://ecs4c16g:8000 python3 scripts/verify_planner_harness_kernel_smoke.py

前置:
  1. docs/nacos/sunshine-orchestrator.yaml → agent.execution.harness.enabled: true
  2. python scripts/sync_nacos.py && python scripts/start.py --restart orchestrator
  3. Gateway / orchestrator / Redis / LLM 可用

回滚旧 Approval/DAG:
  harness.enabled: false → sync_nacos → restart orchestrator

环境变量:
  GATEWAY_URL, REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, HARNESS_SMOKE_TIMEOUT_SEC
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

from sunshine_lib import ensure_redis  # noqa: E402

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000").rstrip("/")
REDIS_HOST = os.environ.get("REDIS_HOST", "ecs4c16g")
REDIS_PORT = int(os.environ.get("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.environ.get("REDIS_PASSWORD", "redis123")
TIMEOUT_SEC = int(os.environ.get("HARNESS_SMOKE_TIMEOUT_SEC", "180"))
NOTEBOOK_PREFIX = "sunshine:plan:notebook:"
NACOS_YAML = ROOT / "docs" / "nacos" / "sunshine-orchestrator.yaml"
ORCH_LOG = ROOT / "orchestrator" / "logs" / "sunshine-orchestrator.log"
SMOKE_QUERY = "分两步：先列出要点再总结。不要调用外部工具。"


def fail(msg: str, *, hint: str | None = None) -> int:
    print(f"\n❌ FAIL: {msg}", file=sys.stderr)
    if hint:
        print(f"   → {hint}", file=sys.stderr)
    return 1


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def preflight_gateway() -> None:
    try:
        resp = requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
        # GET 可能 4xx/5xx，只要 TCP/HTTP 可达即可
        _ = resp.status_code
    except requests.RequestException as exc:
        raise RuntimeError(
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). "
            "请先启动全链路: python scripts/start.py"
        ) from exc


def assert_yaml_harness_enabled() -> bool:
    text = NACOS_YAML.read_text(encoding="utf-8")
    # 匹配 harness 块内第一个 enabled
    m = re.search(
        r"harness:\s*\n(?:[ \t]+#.*\n)*[ \t]+enabled:\s*(true|false)",
        text,
    )
    if not m:
        print(f"⚠ 无法解析 {NACOS_YAML} 中 harness.enabled，继续 live 探测")
        return False
    enabled = m.group(1) == "true"
    print(f"Nacos yaml harness.enabled={enabled} ({NACOS_YAML.relative_to(ROOT)})")
    if not enabled:
        print(
            "⚠ yaml 为 false：runtime 若未 sync，将走旧 Approval/DAG。"
            "冒烟需要 true → sync_nacos → restart orchestrator"
        )
    return enabled


def setup_auth() -> tuple[str, str]:
    user = f"harness_smoke_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "HarnessSmoke"},
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
    return token, str(conv_id)


def chat_sse(token: str, conv_id: str, query: str) -> str:
    import shutil

    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body = {
        "content": query,
        "conversationId": conv_id,
        "executionMode": "pro",
    }
    payload = json.dumps(body, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [
                curl, "-N", "-s", "-m", str(TIMEOUT_SEC),
                "-X", "POST", f"{GATEWAY_URL}/api/chat/stream",
                "-H", f"Authorization: Bearer {token}",
                "-H", "Content-Type: application/json",
                "--data-binary", f"@{tmp}",
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not (raw or "").strip():
            raise RuntimeError(f"SSE failed curl exit={proc.returncode}")
        return raw or ""
    finally:
        os.unlink(tmp)


def parse_sse_events(raw: str) -> list[dict]:
    events: list[dict] = []
    for line in raw.splitlines():
        line = line.rstrip("\r")
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload:
            continue
        try:
            events.append(json.loads(payload))
        except json.JSONDecodeError:
            continue
    return events


def harness_sse_signals(events: list[dict]) -> dict:
    """Harness 特征：plan/worker 步；旧路径常见 confirmation（Approval）。"""
    steps = [e for e in events if e.get("type") == "step"]
    confirmations = [e for e in events if e.get("type") == "confirmation"]
    plan_steps = []
    worker_steps = []
    for s in steps:
        sid = str(s.get("id") or "")
        phase = str(s.get("phase") or "")
        if phase == "plan" or sid == "plan" or sid.startswith("plan-R"):
            plan_steps.append(s)
        if phase == "worker" or sid.startswith("worker-"):
            worker_steps.append(s)
    return {
        "plan_steps": plan_steps,
        "worker_steps": worker_steps,
        "confirmations": confirmations,
        "step_count": len(steps),
    }


def redis_notebook(conv_id: str) -> str | None:
    ensure_redis()
    import redis

    client = redis.Redis(
        host=REDIS_HOST,
        port=REDIS_PORT,
        password=REDIS_PASSWORD or None,
        decode_responses=True,
        socket_connect_timeout=5,
    )
    key = f"{NOTEBOOK_PREFIX}{conv_id}"
    val = client.get(key)
    if val:
        return key
    # 兜底：扫描前缀（sessionId 异常时仍可证明 harness 写过 notebook）
    for k in client.scan_iter(f"{NOTEBOOK_PREFIX}*", count=50):
        if conv_id in k:
            return k
    return None


def log_mentions_harness(since_bytes: int) -> bool:
    if not ORCH_LOG.is_file():
        return False
    try:
        with ORCH_LOG.open("rb") as f:
            f.seek(max(0, since_bytes))
            chunk = f.read().decode("utf-8", errors="replace")
    except OSError:
        return False
    return "PlannerHarnessLoop" in chunk or "PlannerHarnessExecutor" in chunk


def wait_assistant(token: str, conv_id: str, max_wait: int = 90) -> dict | None:
    deadline = time.time() + max_wait
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants:
            last = assistants[-1]
            if last.get("status") in ("completed", "interrupted", "failed"):
                return last
        time.sleep(2)
    return None


def main() -> int:
    print("=== Planner Harness Kernel Smoke ===")
    print(f"Gateway={GATEWAY_URL} timeout={TIMEOUT_SEC}s")
    print(f"Redis={REDIS_HOST}:{REDIS_PORT} prefix={NOTEBOOK_PREFIX}")
    print(
        "说明: harness.enabled=false 时 pro 语义降级；"
        "本冒烟要求 enabled=true。"
    )

    yaml_ok = assert_yaml_harness_enabled()
    try:
        preflight_gateway()
    except RuntimeError as exc:
        return fail(str(exc))

    log_size = ORCH_LOG.stat().st_size if ORCH_LOG.is_file() else 0

    try:
        token, conv_id = setup_auth()
    except Exception as exc:
        return fail(
            f"鉴权失败: {exc}",
            hint="确认 auth-center / gateway 正常，或检查 GATEWAY_URL",
        )
    print(f"conversationId={conv_id}")
    print(f"query={SMOKE_QUERY}")

    try:
        raw = chat_sse(token, conv_id, SMOKE_QUERY)
    except Exception as exc:
        return fail(f"SSE 调用失败: {exc}")

    events = parse_sse_events(raw)
    signals = harness_sse_signals(events)
    print(
        f"SSE: steps={signals['step_count']} plan={len(signals['plan_steps'])} "
        f"worker={len(signals['worker_steps'])} confirmation={len(signals['confirmations'])}"
    )

    assistant = wait_assistant(token, conv_id, max_wait=min(60, TIMEOUT_SEC))
    if assistant:
        print(f"assistant.status={assistant.get('status')}")

    notebook_key = None
    try:
        notebook_key = redis_notebook(conv_id)
    except Exception as exc:
        print(f"⚠ Redis 探测失败: {exc}")

    log_hit = log_mentions_harness(log_size)
    print(f"Redis notebook key={notebook_key or '(none)'}")
    print(f"orchestrator log harness mention={log_hit}")

    # 旧 Approval 路径：出现 confirmation 且无 plan/worker harness 步 → 判定未启用 harness
    if signals["confirmations"] and not signals["plan_steps"] and not notebook_key and not log_hit:
        return fail(
            "疑似 harness 未启用（SSE confirmation 且无 harness 证据）",
            hint=(
                "设置 docs/nacos/sunshine-orchestrator.yaml harness.enabled=true，"
                "然后: python scripts/sync_nacos.py && "
                "python scripts/start.py --restart orchestrator"
            ),
        )

    evidence = []
    if notebook_key:
        evidence.append(f"redis:{notebook_key}")
    if log_hit:
        evidence.append("log:PlannerHarness*")
    if signals["plan_steps"]:
        evidence.append(f"sse:plan×{len(signals['plan_steps'])}")
    if signals["worker_steps"]:
        evidence.append(f"sse:worker×{len(signals['worker_steps'])}")

    if not evidence:
        hint = (
            "无 Redis notebook / harness 日志 / plan|worker SSE。"
            "确认 harness.enabled=true 已 sync 并重启 orchestrator；"
            "完整 H-7 见后续 plan。"
        )
        if not yaml_ok:
            hint = (
                "yaml harness.enabled=false。"
                "改为 true 后 sync_nacos + restart orchestrator 再跑本脚本。"
            )
        return fail("未观察到 harness 内核证据", hint=hint)

    print(f"\n✅ PASS evidence={evidence}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n中断", file=sys.stderr)
        raise SystemExit(130)
