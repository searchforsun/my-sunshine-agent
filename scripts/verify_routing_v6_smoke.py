#!/usr/bin/env python3
"""Unified Routing v6 三模式冒烟（V1 / V3 / V4 / V5）。

| Case | 请求 | 期望 |
|------|------|------|
| V1 | executionMode=fast | ReAct（intent=fast；无 harness notebook 要求） |
| V3 | executionMode=pro | Redis sunshine:plan:notebook:* 或 harness 日志（需 harness.enabled） |
| V4 | workflow + #已知模板 | 静态 Workflow（intent=workflow:…） |
| V5 | workflow 无候选 | 失败/引导，不得变成 ReAct 成功 |

用法:
  python3 scripts/verify_routing_v6_smoke.py
  GATEWAY_URL=http://ecs4c16g:8000 python3 scripts/verify_routing_v6_smoke.py

前置:
  Gateway / auth / orchestrator / Redis / LLM 可用。
  V3 需要 docs/nacos/sunshine-orchestrator.yaml → agent.execution.harness.enabled: true
  若改 yaml: python scripts/sync_nacos.py && python scripts/start.py --restart orchestrator

环境变量:
  GATEWAY_URL, REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, ROUTING_V6_SMOKE_TIMEOUT_SEC
"""
from __future__ import annotations

import json
import os
import re
import shutil
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
TIMEOUT_SEC = int(os.environ.get("ROUTING_V6_SMOKE_TIMEOUT_SEC", "180"))
NOTEBOOK_PREFIX = "sunshine:plan:notebook:"
NACOS_YAML = ROOT / "docs" / "nacos" / "sunshine-orchestrator.yaml"
ORCH_LOG = ROOT / "orchestrator" / "logs" / "sunshine-orchestrator.log"

V1_QUERY = "用一句话介绍什么是阳光智能体平台。"
V3_QUERY = "分两步：先列出要点再总结。不要调用外部工具。"
V4_QUERY = "#knowledge-qa 青松假有多少天、怎么申请"
V5_QUERY = "完全无关的闲聊xyz_routing_v6_no_wf_candidate"
WORKFLOW_MISS_HINT = "未匹配到可用的工作流模板"


def fail_line(msg: str, *, hint: str | None = None) -> None:
    print(f"  ❌ {msg}", file=sys.stderr)
    if hint:
        print(f"     → {hint}", file=sys.stderr)


def ok_line(msg: str) -> None:
    print(f"  ✅ {msg}")


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
        _ = resp.status_code
    except requests.RequestException as exc:
        raise RuntimeError(
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). "
            "请先启动全链路: python scripts/start.py"
        ) from exc


def assert_yaml_harness_enabled() -> bool:
    text = NACOS_YAML.read_text(encoding="utf-8")
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
            "⚠ yaml harness.enabled=false：V3 需要 true → sync_nacos → restart orchestrator"
        )
    return enabled


def setup_auth() -> str:
    user = f"routing_v6_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "RoutingV6Smoke"},
        None,
    )
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    return token


def new_conversation(token: str) -> str:
    conv = auth_json("POST", "/api/conversations", None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return str(conv_id)


def effective_http_code(http_code: int, body: str) -> int:
    if http_code >= 400:
        return http_code
    text = body.strip()
    if not text:
        return http_code
    try:
        first = text.split("\n", 1)[0].strip()
        if first.startswith("{"):
            payload = json.loads(first)
            wrapped = payload.get("code")
            if isinstance(wrapped, int) and wrapped >= 400:
                return wrapped
    except json.JSONDecodeError:
        pass
    if WORKFLOW_MISS_HINT in text or "orch_workflow_template_not_found" in text:
        return 400
    return http_code


def chat_sse(token: str, conv_id: str, query: str, *, execution_mode: str) -> tuple[int, str]:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body = {
        "content": query,
        "conversationId": conv_id,
        "executionMode": execution_mode,
    }
    payload = json.dumps(body, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [
                curl, "-N", "-s", "-w", "\n__HTTP_CODE__%{http_code}",
                "-m", str(TIMEOUT_SEC),
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
        raw = (proc.stdout or "") + (proc.stderr or "")
        code = 200
        if "__HTTP_CODE__" in raw:
            body_text, _, tail = raw.rpartition("__HTTP_CODE__")
            raw = body_text
            try:
                code = int(tail.strip())
            except ValueError:
                code = proc.returncode or 500
        return effective_http_code(code, raw), raw or ""
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


def sse_signals(events: list[dict]) -> dict:
    steps = [e for e in events if e.get("type") == "step"]
    errors = [e for e in events if e.get("type") in ("error", "fail", "failed")]
    plan_steps = []
    worker_steps = []
    think_steps = []
    tool_steps = []
    workflowish = []
    for s in steps:
        sid = str(s.get("id") or "")
        phase = str(s.get("phase") or "")
        if phase == "plan" or sid == "plan" or sid.startswith("plan-R") or sid.startswith("plan"):
            plan_steps.append(s)
        if phase == "worker" or sid.startswith("worker-"):
            worker_steps.append(s)
        if phase == "think" or sid.startswith("think"):
            think_steps.append(s)
        if phase == "tool" or sid.startswith("tool"):
            tool_steps.append(s)
        if "workflow" in phase.lower() or sid.startswith("node-") or sid == "plan" and phase == "plan":
            workflowish.append(s)
    return {
        "steps": steps,
        "errors": errors,
        "plan_steps": plan_steps,
        "worker_steps": worker_steps,
        "think_steps": think_steps,
        "tool_steps": tool_steps,
        "workflowish": workflowish,
        "step_count": len(steps),
        "raw_error_text": " ".join(
            str(e.get("message") or e.get("msg") or e.get("content") or "") for e in errors
        ),
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


def parse_steps(raw_steps) -> list:
    if isinstance(raw_steps, list):
        return raw_steps
    if isinstance(raw_steps, str) and raw_steps.strip():
        try:
            return json.loads(raw_steps)
        except json.JSONDecodeError:
            return []
    return []


def intent_metadata(assistant: dict | None) -> dict:
    if not assistant:
        return {}
    for step in parse_steps(assistant.get("steps")):
        if step.get("id") == "intent":
            return step.get("metadata") or {}
    return {}


def run_chat(
    token: str,
    *,
    case_id: str,
    execution_mode: str,
    query: str,
    log_size: int,
) -> dict:
    conv_id = new_conversation(token)
    print(f"\n[{case_id}] mode={execution_mode} conv={conv_id}")
    print(f"  query={query[:72]}{'…' if len(query) > 72 else ''}")
    http_code, raw = chat_sse(token, conv_id, query, execution_mode=execution_mode)
    events = parse_sse_events(raw)
    signals = sse_signals(events)
    assistant = None
    if http_code < 400:
        try:
            assistant = wait_assistant(token, conv_id, max_wait=min(90, TIMEOUT_SEC))
        except Exception as exc:
            print(f"  ⚠ wait_assistant: {exc}")
    notebook_key = None
    try:
        notebook_key = redis_notebook(conv_id)
    except Exception as exc:
        print(f"  ⚠ Redis: {exc}")
    log_hit = log_mentions_harness(log_size)
    intent = (assistant or {}).get("intent")
    status = (assistant or {}).get("status")
    workflow_id = (assistant or {}).get("workflowId")
    meta = intent_metadata(assistant)
    routing_reason = meta.get("routingReason")
    print(
        f"  http={http_code} status={status} intent={intent!r} "
        f"workflowId={workflow_id!r} reason={routing_reason!r}"
    )
    print(
        f"  SSE steps={signals['step_count']} think={len(signals['think_steps'])} "
        f"plan={len(signals['plan_steps'])} worker={len(signals['worker_steps'])} "
        f"errors={len(signals['errors'])}"
    )
    print(f"  notebook={notebook_key or '(none)'} harness_log={log_hit}")
    return {
        "case": case_id,
        "conv_id": conv_id,
        "http_code": http_code,
        "raw": raw,
        "signals": signals,
        "assistant": assistant,
        "intent": intent,
        "status": status,
        "workflow_id": workflow_id,
        "routing_reason": routing_reason,
        "notebook_key": notebook_key,
        "log_hit": log_hit,
    }


def judge_v1(r: dict) -> tuple[bool, str]:
    """fast → ReAct；不要求 harness notebook。"""
    if r["http_code"] >= 400:
        return False, f"unexpected http={r['http_code']}"
    intent = str(r.get("intent") or "")
    # 协议 wire：fast；兼容旧 react 标签
    if intent in ("fast", "react") or intent.startswith("fast"):
        return True, f"intent={intent}"
    # 无 intent 时：有 think 且无 worker harness 步，视为 ReAct 路径
    sig = r["signals"]
    if sig["think_steps"] and not sig["worker_steps"] and not r.get("notebook_key"):
        return True, f"sse:think×{len(sig['think_steps'])} (no harness notebook)"
    if r.get("status") == "completed" and not r.get("notebook_key") and not sig["worker_steps"]:
        if intent.startswith("workflow:") or intent in ("pro", "plan-workflow"):
            return False, f"wrong path intent={intent}"
        return True, f"completed without harness; intent={intent or '(none)'}"
    return False, f"not ReAct-like intent={intent!r} status={r.get('status')}"


def judge_v3(r: dict, *, yaml_harness: bool) -> tuple[bool, str]:
    """pro → PlannerHarness；证据：notebook / log / plan|worker SSE。"""
    if r["http_code"] >= 400:
        hint = ""
        if not yaml_harness:
            hint = " yaml harness.enabled=false"
        return False, f"http={r['http_code']}{hint} raw_has_err={WORKFLOW_MISS_HINT in (r.get('raw') or '')}"
    intent = str(r.get("intent") or "")
    if intent in ("fast", "react") and not r.get("notebook_key") and not r.get("log_hit"):
        return False, f"silently fell to fast/react intent={intent}"
    evidence = []
    if r.get("notebook_key"):
        evidence.append(f"redis:{r['notebook_key']}")
    if r.get("log_hit"):
        evidence.append("log:PlannerHarness*")
    sig = r["signals"]
    if sig["plan_steps"]:
        evidence.append(f"sse:plan×{len(sig['plan_steps'])}")
    if sig["worker_steps"]:
        evidence.append(f"sse:worker×{len(sig['worker_steps'])}")
    if intent in ("pro", "plan-workflow"):
        evidence.append(f"intent={intent}")
    if evidence and (r.get("notebook_key") or r.get("log_hit") or sig["plan_steps"] or sig["worker_steps"]):
        return True, ",".join(evidence)
    if not yaml_harness:
        return False, "no harness evidence; set harness.enabled=true + sync_nacos + restart orchestrator"
    return False, f"no harness evidence intent={intent!r} status={r.get('status')}"


def judge_v4(r: dict) -> tuple[bool, str]:
    """workflow + #knowledge-qa → 静态 Workflow。"""
    if r["http_code"] >= 400:
        return False, f"unexpected http={r['http_code']}"
    intent = str(r.get("intent") or "")
    wf = r.get("workflow_id") or ""
    if not wf and intent.startswith("workflow:"):
        wf = intent.split(":", 1)[1]
    if wf == "knowledge-qa" or intent == "workflow:knowledge-qa":
        return True, f"workflowId={wf} intent={intent}"
    if intent.startswith("workflow:") and wf:
        return True, f"workflowId={wf} intent={intent}"
    # plan 画布步 + 非 react 成功
    sig = r["signals"]
    if sig["plan_steps"] and intent not in ("fast", "react", "pro", "plan-workflow"):
        return True, f"sse:plan + intent={intent}"
    if intent in ("fast", "react") or (r.get("status") == "completed" and not wf and not intent.startswith("workflow:")):
        return False, f"degraded to ReAct intent={intent!r}"
    return False, f"not static workflow intent={intent!r} workflowId={wf!r}"


def judge_v5(r: dict) -> tuple[bool, str]:
    """workflow 无候选 → 失败，不得 ReAct 成功。"""
    raw = r.get("raw") or ""
    sig = r["signals"]
    miss = (
        WORKFLOW_MISS_HINT in raw
        or "orch_workflow_template_not_found" in raw
        or WORKFLOW_MISS_HINT in sig.get("raw_error_text", "")
    )
    if r["http_code"] >= 400 or miss:
        return True, f"explicit fail http={r['http_code']} miss_hint={miss}"
    intent = str(r.get("intent") or "")
    status = r.get("status")
    if status == "failed":
        return True, f"assistant failed intent={intent!r}"
    if status == "completed" and (intent in ("fast", "react") or intent.startswith("fast")):
        return False, f"silently degraded to ReAct success intent={intent}"
    if status == "completed" and intent.startswith("workflow:"):
        return False, f"unexpected workflow hit intent={intent}"
    if status == "completed":
        return False, f"completed without failure intent={intent!r} (must not succeed as ReAct)"
    # interrupted / none：若无 react 成功内容，可接受为未完成失败路径
    if status in (None, "interrupted") and intent not in ("fast", "react"):
        if miss or r["http_code"] >= 400:
            return True, f"non-success status={status}"
    return False, f"expected workflow miss failure; got http={r['http_code']} status={status} intent={intent!r}"


def main() -> int:
    print("=== Routing v6 Three-Mode Smoke (V1/V3/V4/V5) ===")
    print(f"Gateway={GATEWAY_URL} timeout={TIMEOUT_SEC}s")
    print(f"Redis={REDIS_HOST}:{REDIS_PORT} prefix={NOTEBOOK_PREFIX}")

    yaml_harness = assert_yaml_harness_enabled()
    try:
        preflight_gateway()
    except RuntimeError as exc:
        print(f"\n❌ FAIL: {exc}", file=sys.stderr)
        return 1

    try:
        token = setup_auth()
    except Exception as exc:
        print(f"\n❌ FAIL: 鉴权失败: {exc}", file=sys.stderr)
        print("   → 确认 auth-center / gateway 正常，或检查 GATEWAY_URL", file=sys.stderr)
        return 1

    results: dict[str, dict] = {}
    judges = {
        "V1": lambda r: judge_v1(r),
        "V3": lambda r: judge_v3(r, yaml_harness=yaml_harness),
        "V4": lambda r: judge_v4(r),
        "V5": lambda r: judge_v5(r),
    }
    cases = [
        ("V1", "fast", V1_QUERY),
        ("V3", "pro", V3_QUERY),
        ("V4", "workflow", V4_QUERY),
        ("V5", "workflow", V5_QUERY),
    ]

    for case_id, mode, query in cases:
        log_size = ORCH_LOG.stat().st_size if ORCH_LOG.is_file() else 0
        try:
            r = run_chat(token, case_id=case_id, execution_mode=mode, query=query, log_size=log_size)
            passed, detail = judges[case_id](r)
            r["pass"] = passed
            r["detail"] = detail
            # 不把巨型 SSE 打进最终报告
            r.pop("raw", None)
            r.pop("assistant", None)
            r.pop("signals", None)
            results[case_id] = r
            if passed:
                ok_line(detail)
            else:
                fail_line(detail)
                if case_id == "V3" and not yaml_harness:
                    fail_line(
                        "harness.enabled=false",
                        hint="docs/nacos/sunshine-orchestrator.yaml → true; "
                        "python scripts/sync_nacos.py && "
                        "python scripts/start.py --restart orchestrator",
                    )
        except Exception as exc:
            results[case_id] = {"case": case_id, "pass": False, "detail": str(exc)}
            fail_line(str(exc))

    failed = [k for k, v in results.items() if not v.get("pass")]
    print("\n=== Report ===")
    print(json.dumps(results, ensure_ascii=False, indent=2, default=str))
    if failed:
        print(f"\n❌ FAIL cases: {failed}", file=sys.stderr)
        if "V3" in failed and not yaml_harness:
            print(
                "   V3 依赖 harness.enabled=true（已在 yaml 标注）。",
                file=sys.stderr,
            )
        return 1
    print("\n✅ PASS all V1/V3/V4/V5")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\n中断", file=sys.stderr)
        raise SystemExit(130)
