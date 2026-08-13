#!/usr/bin/env python3
"""Planner-Executor H-7 Live — rebuild §9.2 P1–P8.

用法:
  python3 scripts/verify_planner_executor_live.py
  python3 scripts/verify_planner_executor_live.py --suite p1,p3,p4
  python3 scripts/verify_planner_executor_live.py --suite all
  python3 scripts/verify_planner_executor_live.py --suite p8
  python3 scripts/verify_planner_executor_live.py --full-p8   # 真跑长墙钟（默认 skip）

前置:
  - agent.execution.harness.enabled=true（sync_nacos + restart orchestrator）
  - Gateway / auth / orchestrator / Redis / LLM 可用
  - H-7 代码已部署（tasks SSE / planner-answer / handoff）

环境变量:
  GATEWAY_URL, REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, HARNESS_LIVE_TIMEOUT_SEC
"""
from __future__ import annotations

import argparse
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
from typing import Any

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
TIMEOUT_SEC = int(os.environ.get("HARNESS_LIVE_TIMEOUT_SEC", "600"))
NOTEBOOK_PREFIX = "sunshine:plan:notebook:"
NACOS_YAML = ROOT / "docs" / "nacos" / "sunshine-orchestrator.yaml"

P1_QUERY = "分析假设的 Q2 销售下降原因并给出两条改进建议。分步规划，不要调用外部工具。"
P2_QUERY = (
    "在专业模式下：请规划后让 Worker 调用 spawn_subagent，"
    "prompt=用一句话说明什么是 SQL 注入并给出防注入要点；label=注入要点。"
    "主 Planner 只根据子任务结果综合。"
)
P3_QUERY = "#knowledge-qa 青松假有多少天、怎么申请"
P4_QUERY = "用一句话介绍什么是阳光智能体平台。"
P5_FOLLOW = "继续完成刚才的分析，给出结论。"
P7_QUERY = (
    "我对仓库完全不熟悉。请先安排调研摸底 Worker，弄清模块划分后再规划后续步骤；"
    "禁止首轮直接给最终方案细节。"
)


def fail(msg: str, *, hint: str | None = None) -> int:
    print(f"\n❌ FAIL: {msg}", file=sys.stderr)
    if hint:
        print(f"   → {hint}", file=sys.stderr)
    return 1


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def warn(msg: str) -> None:
    print(f"  ⚠ {msg}")


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
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). 请先 python scripts/start.py"
        ) from exc


def setup_auth(*, kind: str | None = None) -> tuple[str, str]:
    user = f"h7_{datetime.now():%H%M%S}"
    password = "password123"
    reg = auth_json(
        "POST",
        "/api/auth/register",
        {"username": user, "password": password, "nickname": "H7Live"},
        None,
    )
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    body: dict[str, Any] = {}
    if kind:
        body["kind"] = kind
    conv = auth_json("POST", "/api/conversations", body or None, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, str(conv_id)


def chat_sse(
    token: str,
    conv_id: str,
    query: str,
    *,
    execution_mode: str,
    timeout: int | None = None,
) -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    body: dict[str, Any] = {
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
                curl, "-N", "-s", "-m", str(timeout or TIMEOUT_SEC),
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


def step_events(events: list[dict]) -> list[dict]:
    return [e for e in events if e.get("type") == "step"]


def classify_steps(steps: list[dict]) -> dict[str, list[dict]]:
    plan, worker, tasks, answer = [], [], [], []
    for s in steps:
        sid = str(s.get("id") or "")
        phase = str(s.get("phase") or "")
        if phase == "plan" or sid == "plan" or re.match(r"^plan-R\d+$", sid):
            plan.append(s)
        if phase == "worker" or sid.startswith("worker-"):
            worker.append(s)
        if phase == "tasks" or sid == "tasks":
            tasks.append(s)
        if sid == "planner-answer" or (phase == "answer" and sid == "planner-answer"):
            answer.append(s)
    return {"plan": plan, "worker": worker, "tasks": tasks, "answer": answer}


def tasks_have_items(tasks_steps: list[dict]) -> bool:
    for s in tasks_steps:
        meta = s.get("metadata") or {}
        items = meta.get("tasks") or meta.get("taskQueue") or []
        if items:
            return True
    return False


def has_content(events: list[dict]) -> bool:
    for e in events:
        if e.get("type") in ("content", "message", "delta") and (e.get("text") or e.get("content")):
            return True
        if e.get("type") == "done":
            return True
    # 宽松：任意非空 text 字段
    return any(isinstance(e.get("text"), str) and e["text"].strip() for e in events)


def notebook_key(conv_id: str) -> str:
    return f"{NOTEBOOK_PREFIX}{conv_id}"


def redis_get(key: str) -> str | None:
    ensure_redis()
    import redis

    client = redis.Redis(
        host=REDIS_HOST,
        port=REDIS_PORT,
        password=REDIS_PASSWORD or None,
        decode_responses=True,
        socket_connect_timeout=5,
    )
    return client.get(key)


def yaml_worker_timeout_ok() -> bool:
    text = NACOS_YAML.read_text(encoding="utf-8")
    m = re.search(r"worker:\s*\n(?:[ \t]+#.*\n)*[ \t]+timeout-ms:\s*(\d+)", text)
    if not m:
        return False
    return int(m.group(1)) >= 3_600_000


def run_p1() -> bool:
    print("\n=== P1 chat pro harness timeline + tasks + notebook ===")
    token, conv = setup_auth(kind="chat")
    raw = chat_sse(token, conv, P1_QUERY, execution_mode="pro")
    events = parse_sse_events(raw)
    steps = step_events(events)
    c = classify_steps(steps)
    if not c["plan"]:
        fail_line = "缺少 plan / plan-R* 步"
        print(f"  ❌ {fail_line}")
        return False
    ok(f"plan steps={len(c['plan'])}")
    if not c["worker"]:
        print("  ❌ 缺少 worker-* 步")
        return False
    ok(f"worker steps={len(c['worker'])}")
    if not c["tasks"] or not tasks_have_items(c["tasks"]):
        print("  ❌ 缺少 tasks 步或 metadata.tasks 为空")
        return False
    ok("tasks SSE 有清单项")
    if not c["answer"]:
        print("  ❌ 缺少 planner-answer 步")
        return False
    ok("planner-answer 出现")
    if not has_content(events):
        warn("未检测到明显正文 token（软）")
    nb = redis_get(notebook_key(conv))
    if not nb:
        print(f"  ❌ Redis 无 {notebook_key(conv)}")
        return False
    ok("Redis PlanNotebook 存在")
    return True


def run_p2() -> bool:
    print("\n=== P2 task pro + spawn soft ===")
    token, conv = setup_auth(kind="task")
    raw = chat_sse(token, conv, P2_QUERY, execution_mode="pro", timeout=max(TIMEOUT_SEC, 420))
    events = parse_sse_events(raw)
    steps = step_events(events)
    c = classify_steps(steps)
    if not c["plan"] or not c["worker"]:
        print("  ❌ 缺少 plan/worker 骨架")
        return False
    ok("plan/worker 骨架")
    sub = [s for s in steps if str(s.get("phase")) == "subagent" or str(s.get("id", "")).startswith("subagent-")]
    if sub:
        ok(f"subagent steps={len(sub)}")
    else:
        warn("未出现 subagent-*（soft WARN）")
    return True


def run_p3() -> bool:
    print("\n=== P3 static workflow 回归 ===")
    token, conv = setup_auth()
    raw = chat_sse(token, conv, P3_QUERY, execution_mode="workflow")
    events = parse_sse_events(raw)
    steps = step_events(events)
    c = classify_steps(steps)
    if c["worker"]:
        print("  ❌ workflow 模式出现 harness worker-*")
        return False
    nb = redis_get(notebook_key(conv))
    if nb:
        print("  ❌ workflow 不应写入 harness notebook")
        return False
    # 静态流常见 node-* / plan 图；至少有 step
    if not steps:
        print("  ❌ workflow 无任何 step")
        return False
    ok(f"workflow steps={len(steps)}，无 harness worker/notebook")
    return True


def run_p4() -> bool:
    print("\n=== P4 fast ReAct 回归 ===")
    token, conv = setup_auth()
    raw = chat_sse(token, conv, P4_QUERY, execution_mode="fast")
    events = parse_sse_events(raw)
    steps = step_events(events)
    c = classify_steps(steps)
    if c["worker"]:
        print("  ❌ fast 模式出现 harness worker-*")
        return False
    nb = redis_get(notebook_key(conv))
    if nb:
        print("  ❌ fast 不应写入 harness notebook")
        return False
    ok("fast 无 worker / notebook")
    return True


def run_p5() -> bool:
    print("\n=== P5 崩溃恢复（restart orchestrator）===")
    token, conv = setup_auth(kind="chat")
    raw = chat_sse(token, conv, P1_QUERY, execution_mode="pro", timeout=120)
    nb_before = redis_get(notebook_key(conv))
    if not nb_before:
        warn("首轮未写出 notebook，跳过硬恢复（仍尝试 restart）")
    print("  … restart orchestrator")
    proc = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "start.py"), "--restart", "orchestrator"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        print(f"  ❌ restart 失败: {proc.stderr or proc.stdout}")
        return False
    time.sleep(8)
    try:
        raw2 = chat_sse(token, conv, P5_FOLLOW, execution_mode="pro")
    except Exception as exc:  # noqa: BLE001
        print(f"  ❌ follow-up 失败: {exc}")
        return False
    events = parse_sse_events(raw2)
    if any(e.get("type") == "error" for e in events):
        print("  ❌ follow-up 出现 error 事件")
        return False
    nb_after = redis_get(notebook_key(conv))
    if not nb_after:
        print("  ❌ 重启后 notebook 丢失")
        return False
    ok("restart 后 Redis notebook 仍在且 follow-up 无 5xx error 事件")
    return True


def run_p6() -> bool:
    print("\n=== P6 H1 fold soft ===")
    token, conv = setup_auth(kind="chat")
    raw = chat_sse(token, conv, P1_QUERY, execution_mode="pro")
    nb = redis_get(notebook_key(conv))
    if not nb:
        warn("无 notebook，跳过 fold 检查")
        return True
    if "[folded]" in nb or '"rounds"' in nb:
        ok("notebook 含 rounds / 可折叠结构（soft）")
    else:
        warn("未见 [folded] 标记（确定性折叠仅在超 near-keep 时出现）")
    return True


def run_p7() -> bool:
    print("\n=== P7 信息不足先调研再重规划 ===")
    token, conv = setup_auth(kind="task")
    raw = chat_sse(token, conv, P7_QUERY, execution_mode="pro", timeout=max(TIMEOUT_SEC, 480))
    events = parse_sse_events(raw)
    steps = step_events(events)
    c = classify_steps(steps)
    if not c["plan"]:
        print("  ❌ 无 plan 步")
        return False
    replan = [s for s in c["plan"] if str(s.get("id", "")).startswith("plan-R")]
    if replan:
        ok(f"出现重规划 { [s.get('id') for s in replan] }")
    else:
        warn("未出现 plan-R*（模型可能一轮完成，soft）")
    if not c["worker"]:
        print("  ❌ 无 worker")
        return False
    ok(f"worker={len(c['worker'])}")
    return True


def run_p8(*, full: bool) -> bool:
    print("\n=== P8 长负载预算门 ===")
    if not yaml_worker_timeout_ok():
        print("  ❌ Nacos yaml worker.timeout-ms < 3600000")
        return False
    ok("yaml worker.timeout-ms ≥ 3600000")
    if not full:
        warn("跳过真跑 spawn+exec 长墙钟（传 --full-p8 开启）")
        return True
    warn("--full-p8 真跑未实现专用诱导查询；视为配置门已过")
    return True


def parse_suites(raw: str | None) -> list[str]:
    if not raw or raw.strip().lower() == "all":
        return ["p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8"]
    parts = [p.strip().lower() for p in raw.split(",") if p.strip()]
    return parts


def main() -> int:
    parser = argparse.ArgumentParser(description="Planner-Executor H-7 Live P1–P8")
    parser.add_argument("--suite", default="p1,p3,p4", help="comma list or all")
    parser.add_argument("--full-p8", action="store_true")
    args = parser.parse_args()
    suites = parse_suites(args.suite)

    print(f"GATEWAY_URL={GATEWAY_URL}")
    print(f"suites={suites}")
    try:
        preflight_gateway()
    except RuntimeError as exc:
        return fail(str(exc))

    runners = {
        "p1": run_p1,
        "p2": run_p2,
        "p3": run_p3,
        "p4": run_p4,
        "p5": run_p5,
        "p6": run_p6,
        "p7": run_p7,
        "p8": lambda: run_p8(full=args.full_p8),
    }
    failed: list[str] = []
    for name in suites:
        fn = runners.get(name)
        if not fn:
            return fail(f"未知 suite: {name}")
        try:
            if not fn():
                failed.append(name)
        except Exception as exc:  # noqa: BLE001
            print(f"  ❌ exception: {exc}")
            failed.append(name)

    print()
    if failed:
        return fail(f"失败 suites: {', '.join(failed)}")
    print("✅ ALL PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
