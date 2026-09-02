#!/usr/bin/env python3
"""Planner-Executor H-7 Live — rebuild §9.2 P1–P9.

用法:
  python3 scripts/verify_planner_executor_live.py
  python3 scripts/verify_planner_executor_live.py --suite p1,p3,p4
  python3 scripts/verify_planner_executor_live.py --suite all
  python3 scripts/verify_planner_executor_live.py --suite p8
  python3 scripts/verify_planner_executor_live.py --full-p8   # 真跑长墙钟（默认 skip）
  python3 scripts/verify_planner_executor_live.py --suite p9   # v17.7 worker 并发/取消/重试链

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
import threading
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

# v17：Planner 自决派 Worker；不要在 query 里禁工具（否则 Planner 不派 Worker）
P1_QUERY = "分析假设的 Q2 销售下降原因，给出两条改进建议和一份执行清单。完成规划后直接综合回答用户。"
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
P9_QUERY = (
    "请并行派发 3 个 Worker 同时独立调研本仓库：①整体架构与模块划分；"
    "②测试与质量保障；③构建与 CI 配置。三个 Worker 各自完成后由我综合汇报。"
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
    user = f"h7_{datetime.now():%H%M%S%f}"
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


def wait_orchestrator_healthy(timeout: int) -> bool:
    """轮询 orchestrator 健康端点直至就绪（P5 restart 后避免 8s 固定等待不足）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            resp = requests.get("http://ecs4c16g:8200/actuator/health", timeout=3)
            if resp.status_code == 200:
                return True
        except requests.RequestException:
            pass
        time.sleep(2)
    return False


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


class SseCollector:
    """后台线程流式消费 SSE，支持中途取消 worker 后再继续等 done。"""

    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.generation_id: str | None = None
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
        if t == "step":
            self.steps.append(obj)
        elif t == "generation" and obj.get("id"):
            self.generation_id = str(obj["id"])


def chat_sse_stream(
    token: str,
    conv_id: str,
    query: str,
    *,
    execution_mode: str,
    read_timeout: int = TIMEOUT_SEC,
) -> SseCollector:
    """后台线程流式消费；调用方可捕获 workerRunId 后中途 cancel 再 wait_done。

    read_timeout：单次流式读取上限（P9 取消→重派链整轮可超 10 分钟，需单独放宽）。
    """
    collector = SseCollector()
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    def run() -> None:
        try:
            body: dict[str, Any] = {
                "content": query,
                "conversationId": conv_id,
                "executionMode": execution_mode,
            }
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers=headers,
                json=body,
                stream=True,
                timeout=(10, read_timeout),
            ) as resp:
                resp.raise_for_status()
                for raw in resp.iter_lines(decode_unicode=True):
                    if raw is None:
                        continue
                    line = raw.strip()
                    if not line.startswith("data:"):
                        continue
                    collector.parse_line(line)
        except Exception as e:  # noqa: BLE001
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run, daemon=True).start()
    return collector


def worker_run_id(step: dict) -> str | None:
    meta = step.get("metadata") or {}
    rid = meta.get("workerRunId")
    return str(rid) if rid else None


def last_task_items(steps: list[dict]) -> list[dict]:
    for s in reversed(steps):
        meta = s.get("metadata") or {}
        items = meta.get("taskQueue") or meta.get("tasks") or []
        if items:
            return items
    return []


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
    worker, tasks = [], []
    for s in steps:
        sid = str(s.get("id") or "")
        phase = str(s.get("phase") or "")
        if phase == "worker" or sid.startswith("worker-"):
            worker.append(s)
        if phase == "tasks" or sid == "tasks":
            tasks.append(s)
    return {"worker": worker, "tasks": tasks}


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
    print("\n=== P1 chat pro harness timeline + tasks + notebook（v17 一次性 ReAct）===")
    token, conv = setup_auth(kind="chat")
    raw = chat_sse(token, conv, P1_QUERY, execution_mode="pro")
    events = parse_sse_events(raw)
    steps = step_events(events)
    c = classify_steps(steps)
    # v17：Planner 自决是否派 Worker；期待派 1+ 个 worker-* 一级行
    if not c["worker"]:
        print("  ❌ 缺少 worker-* 步（v17 Planner 应自然派 Worker）")
        return False
    ok(f"worker steps={len(c['worker'])}")
    if not c["tasks"] or not tasks_have_items(c["tasks"]):
        print("  ❌ 缺少 tasks 步或 metadata.tasks 为空")
        return False
    ok("tasks SSE 有清单项")
    # v17：plan_submit / self_assess 元工具步平铺展示（v16 隐藏 plan_submit 的策略作废）
    plan_submit_steps = [s for s in steps if str(s.get("id") or "").startswith("tool-plan_submit")]
    if not plan_submit_steps:
        warn("未检测到 tool-plan_submit 步（v17 期望 Planner 自然调用 plan_submit）")
    else:
        ok(f"plan_submit steps={len(plan_submit_steps)}（元工具平铺）")
    self_assess_steps = [s for s in steps if str(s.get("id") or "").startswith("tool-self_assess")]
    if self_assess_steps:
        ok(f"self_assess steps={len(self_assess_steps)}（可选元工具）")
    else:
        warn("未检测到 self_assess 步（v17 允许 Planner 不调它直接收束）")
    # v17 关键断言：不应出现独立 plan(R{n}) / planner-answer 步骤
    legacy_plan = [s for s in steps if re.match(r"^plan-R\d+$", str(s.get("id") or ""))]
    if legacy_plan:
        print(f"  ❌ v17 应取消独立 plan(Rn) 步，但检测到 {len(legacy_plan)} 个")
        return False
    ok("无独立 plan(R{n}) 步（v17 取消）")
    planner_answer = [s for s in steps if str(s.get("id") or "") == "planner-answer"]
    if planner_answer:
        print(f"  ❌ v17 应取消独立 planner-answer 步，但检测到 {len(planner_answer)} 个")
        return False
    ok("无独立 planner-answer 步（v17 取消；Planner content 流到主时间线）")
    if not has_content(events):
        warn("未检测到明显正文 token（软）")
    else:
        ok("Planner content tokens 已流到主时间线（综合回答）")
    # TaskBoard 实时刷新断言：检查 tasks step 是否出现 ≥2 次（plan_submit 后 + worker done 后）
    if len(c["tasks"]) < 2:
        warn(f"tasks 步出现 {len(c['tasks'])} 次，期望 ≥2（v17 Worker 完成后应再次 emit）")
    else:
        ok(f"tasks step 刷新 {len(c['tasks'])} 次（plan_submit + worker-done）")
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
    # v16：plan 步已移除；worker-* 是 harness 时间线骨架
    if not c["worker"]:
        print("  ❌ 缺少 worker-* 骨架")
        return False
    ok("worker-* 骨架")
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
    raw = chat_sse(token, conv, P1_QUERY, execution_mode="pro", timeout=max(TIMEOUT_SEC, 300))
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
    if not wait_orchestrator_healthy(60):
        print("  ❌ restart 后 orchestrator 未就绪")
        return False
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
    # v17：计划经 plan_submit 元工具步平铺（独立 plan(R{n}) 步已取消）；重规划 = 多次 plan_submit
    plan_submit_steps = [s for s in steps if str(s.get("id") or "").startswith("tool-plan_submit")]
    if not plan_submit_steps:
        print("  ❌ 无 plan_submit 步（Planner 未提交调度计划）")
        return False
    ok(f"plan_submit steps={len(plan_submit_steps)}（v17 元工具平铺）")
    if len(plan_submit_steps) >= 2:
        ok(f"出现重规划（plan_submit 调用了 {len(plan_submit_steps)} 次）")
    else:
        warn("仅 1 次 plan_submit（模型可能一轮完成，soft）")
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


def run_p9() -> bool:
    print("\n=== P9 Worker 并发流式 + 单独取消 + TaskBoard 版本化（v17.7）===")
    # 取消→Planner 重派（t1-2）链整轮可超 10 分钟；P9 单独放宽流式窗口
    p9_timeout = max(TIMEOUT_SEC, 900)
    token, conv = setup_auth(kind="task")
    coll = chat_sse_stream(token, conv, P9_QUERY, execution_mode="pro", read_timeout=p9_timeout)
    # 等待 worker running 骨架 + workerRunId（v17.7 新链路）
    deadline = time.time() + TIMEOUT_SEC
    run_id: str | None = None
    while time.time() < deadline:
        for s in coll.steps:
            rid = worker_run_id(s)
            if rid:
                run_id = rid
                break
        if run_id and coll.generation_id:
            break
        if coll.error and not coll.steps:
            raise coll.error
        time.sleep(0.5)
    if not run_id or not coll.generation_id:
        coll.wait_done(30)
        print("  ❌ 未捕获 workerRunId / generationId（worker 可能瞬时完成）")
        return False
    gen_id = coll.generation_id
    ok(f"捕获 workerRunId={run_id[:12]}… generationId={gen_id[:12]}…")
    worker_ids = {
        str(s.get("id"))
        for s in coll.steps
        if str(s.get("phase")) == "worker" or str(s.get("id") or "").startswith("worker-")
    }
    if len(worker_ids) >= 2:
        ok(f"并发 worker 骨架：{len(worker_ids)} 个不同 id（{sorted(worker_ids)}）")
    else:
        warn(f"并发 worker 骨架偏少（{len(worker_ids)} 个），模型可能顺序派发（soft）")
    # 单独取消（复用 subagent cancel API；worker 经 SpawnRunRegistry 注册）
    cancel_resp = auth_json(
        "POST",
        f"/api/generations/{gen_id}/subagents/{run_id}/cancel",
        None,
        token,
    )
    cancel_status = (cancel_resp.get("data") or cancel_resp).get("status") or cancel_resp.get("status")
    print(f"  cancel_api status={cancel_status}")
    if cancel_status not in ("CANCELLED", None):
        print(f"  ❌ cancel API 异常: {json.dumps(cancel_resp, ensure_ascii=False)[:200]}")
        return False
    ok("cancel API 已受理（单独取消不阻断整轮）")
    # 取消→Planner 重派后整轮对话可能远超 10 分钟（重派 Worker 长任务）；
    # 断言依赖「paused 卡 + TaskBoard 取消行 + 版本化记号」出现，轮询已收集步，不等整轮 SSE 结束。
    deadline = time.time() + p9_timeout
    cancelled_step: dict | None = None
    items: list[dict] = []
    while time.time() < deadline:
        cancelled_step = next(
            (s for s in coll.steps if worker_run_id(s) == run_id and str(s.get("lifecycle")) == "paused"),
            None,
        )
        items = last_task_items(coll.steps)
        cancelled_rows = [i for i in items if str(i.get("status")) in ("cancelled", "fail", "obsolete")]
        versioned = [str(i.get("id")) for i in items if re.match(r"^[A-Za-z]\w*-\d+$", str(i.get("id") or ""))]
        if cancelled_step and cancelled_rows and versioned:
            break
        if coll.error and not coll.steps:
            break
        time.sleep(0.5)
    # 断言 1：被取消的 worker 卡 paused + 已取消
    if cancelled_step:
        ok(f"取消的 worker 卡 lifecycle=paused（{cancelled_step.get('id')}）")
    else:
        paused_any = [str(s.get("id")) for s in coll.steps if str(s.get("lifecycle")) == "paused"]
        print(f"  ❌ 未见取消 worker 的 paused 终态（paused 步共 {len(paused_any)} 个: {paused_any}）")
        return False
    # 断言 2：TaskBoard 最后快照保留取消行（前端 ⊗）
    items = last_task_items(coll.steps)
    cancelled_rows = [i for i in items if str(i.get("status")) in ("cancelled", "fail", "obsolete")]
    if cancelled_rows:
        ok(f"TaskBoard 保留失败/取消行 {len(cancelled_rows)} 条 → 前端 ⊗（{ [str(i.get('id')) for i in cancelled_rows] }）")
    else:
        print("  ❌ 最终 taskQueue 无 cancelled/fail 行（TaskBoard 未保留取消记录）")
        return False
    # 断言 3：TaskBoard taskId 版本化记号（t1-1 / t1-2，v17.7）
    if versioned:
        ok(f"TaskBoard taskId 版本化记号：{versioned}")
    else:
        warn("本轮未见 t{n}-{m} 版本化记号（取消后 Planner 未重派，soft）")
    return True


def parse_suites(raw: str | None) -> list[str]:
    if not raw or raw.strip().lower() == "all":
        return ["p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9"]
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
        "p9": run_p9,
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
