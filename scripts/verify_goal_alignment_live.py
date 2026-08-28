#!/usr/bin/env python3
"""react-goal-alignment 4.7.7 Live 验收 — spec 2026-07-27-react-goal-alignment-design.md §9.2。

两中间件默认关灰度；本脚本按 Phase 逐段热切（@RefreshScope，不重启）：
  Phase 1 双开关关（线上默认）
    A    G3/G4 回归：简单单轮「你好」→ 无 [GoalAlignment]/[FailureBudget] 注入日志、无 tasks 步
  Phase 2 开 goal-check（every-n-think=3）
    B    G1 多步调研句引导 todo_write 建板 + 多轮工具 → [GoalAlignment] goal-check 注入日志出现
  Phase 3 关 goal-check、开 tool-failure-budget（same-signature-max=2）
    C    G2 引导对不存在路径连续 3 次同参数 sandbox exec（[ERROR] 契约 → AS ERROR state）
         → [FailureBudget] budget 达阈值注入强提示日志出现；SSE tool 步 after=「连续失败，需调整方案」
  Phase 4 还原双开关 false

用法:
  python3 scripts/verify_goal_alignment_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  TIMEOUT_SEC（单轮 SSE 上限，默认 300）
  ORCH_LOG（orchestrator 日志路径，默认 ../logs/sunshine-orchestrator.log）
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
import uuid
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("TIMEOUT_SEC", "300"))
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(SCRIPT_DIR, "..")
LOG_PATH = os.environ.get("ORCH_LOG", os.path.join(ROOT, "logs", "sunshine-orchestrator.log"))
NACOS_YAML = os.path.join(ROOT, "docs", "nacos", "sunshine-orchestrator.yaml")

RESULTS: list[str] = []


def fail(msg: str) -> None:
    RESULTS.append(msg)
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)


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


def register_and_login(kind: str = "chat") -> tuple[str, str]:
    """注册 + 登录 + 建会话；返回（token, conv_id）。"""
    user = f"goalalg_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "GoalAlg"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    data = login.get("data") or {}
    token = data.get("tokenValue") or data.get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    conv = auth_json("POST", "/api/conversations", {"kind": kind}, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, str(conv_id)


def chat_sse(token: str, conv_id: str, query: str) -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found")
    payload = json.dumps(
        {"content": query, "conversationId": conv_id, "executionMode": "fast"},
        ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [curl, "-N", "-s", "-m", str(TIMEOUT_SEC), "-X", "POST",
             f"{GATEWAY_URL}/api/chat/stream",
             "-H", f"Authorization: Bearer {token}",
             "-H", "Content-Type: application/json",
             "--data-binary", f"@{tmp}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE failed curl exit={proc.returncode}")
        return raw
    finally:
        os.unlink(tmp)


def sse_text(raw: str) -> str:
    parts: list[str] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            obj = json.loads(payload)
        except Exception:
            continue
        if obj.get("type") in ("content", "message", "delta"):
            text = obj.get("text") or obj.get("content")
            if isinstance(text, str) and text.strip():
                parts.append(text)
    return "".join(parts)


def sse_tool_after(raw: str) -> list[str]:
    """收集 SSE 中 tool 步收口（type=step, phase=tool）的 summary.after 文案（G2 Timeline 断言）。"""
    afters: list[str] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:"):].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            obj = json.loads(payload)
        except Exception:
            continue
        phase = str(obj.get("phase") or obj.get("kind") or "")
        summary = obj.get("summary") or {}
        after = summary.get("after") if isinstance(summary, dict) else None
        if "tool" in phase and isinstance(after, str) and after.strip():
            afters.append(after)
    return afters


def log_offset() -> int:
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
            return len(f.read().splitlines())
    except OSError:
        return 0


def log_since(offset: int) -> str:
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as f:
            return "\n".join(f.read().splitlines()[offset:])
    except OSError as exc:
        warn(f"orchestrator 日志不可读（{exc}）")
        return ""


def log_wait_since(offset: int, pattern: str, timeout: int = 90) -> str | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = log_since(offset)
        for line in reversed(text.splitlines()):
            if pattern in line:
                return line
        time.sleep(2)
    return None


def set_switch(section: str, enabled: bool) -> None:
    """热切 Nacos 开关（@RefreshScope 生效）；section=goal-check|tool-failure-budget。
    只同步 sunshine-orchestrator.yaml（避免全量上传的副作用）。"""
    path = NACOS_YAML
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    section_idx = next((i for i, l in enumerate(lines) if l.strip().startswith(section + ":")), -1)
    if section_idx < 0:
        raise RuntimeError(f"Nacos yaml 未找到 {section} 段（path={path}）")
    enabled_idx = next((i for i in range(section_idx, min(section_idx + 3, len(lines)))
                        if "enabled:" in lines[i]), -1)
    if enabled_idx < 0:
        raise RuntimeError(f"Nacos yaml 未找到 {section}.enabled 行（path={path}）")
    indent = lines[enabled_idx][:len(lines[enabled_idx]) - len(lines[enabled_idx].lstrip())]
    lines[enabled_idx] = f"{indent}enabled: {'true' if enabled else 'false'}\n"
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(lines)
    sync = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, "sync_nacos.py"),
         "--data-id", "sunshine-orchestrator.yaml"],
        capture_output=True, text=True, timeout=120)
    if sync.returncode != 0:
        raise RuntimeError(f"sync_nacos failed: {sync.stdout} {sync.stderr}")
    time.sleep(8)


def _phase_c_attempt(attempt: int) -> bool:
    """单次 C 段驱动：返回是否成功（预算注入日志出现）。每次用全新会话，避免上下文干扰。"""
    offset_c = log_offset()
    token_c, conv_c = register_and_login("chat")
    # 三条完全相同命令（同工具名 per-tool + 同参数指纹 same-signature 双维度都累积）；
    # ls 命中 exec 只读白名单免 HITL；文件不存在 → exitCode≠0 → [ERROR] 契约 → ERROR state；
    # 措辞明确「必失败且不许换命令/路径」，防止模型失败后自行调整导致计数清零
    query_c = (
        "请依次执行以下三条完全相同的命令（全部执行完再统一回复），注意：\n"
        "文件 /workspace/goal-alignment-notes.txt 一定不存在，三条命令都会执行失败，这是预期现象；\n"
        "请严格按清单逐条执行，不要更换命令、不要检查其他路径：\n"
        "1) ls -l /workspace/goal-alignment-notes.txt\n"
        "2) ls -l /workspace/goal-alignment-notes.txt\n"
        "3) ls -l /workspace/goal-alignment-notes.txt")
    print(f"  [C] query={query_c[:44]}...")
    raw = chat_sse(token_c, conv_c, query_c)
    print(f"  [C] reply_len={len(sse_text(raw))}")
    c_afters = sse_tool_after(raw)
    inject_c = log_wait_since(offset_c, "[FailureBudget] budget 达阈值注入强提示")
    if inject_c:
        ok(f"C: 失败预算注入出现（{inject_c.strip()}）")
    else:
        fail(f"C(attempt {attempt}): 未出现 [FailureBudget] budget 达阈值注入强提示日志")
    if any("连续失败" in a for a in c_afters):
        ok(f"C: Timeline tool 步 after 含「连续失败」（{c_afters[:2]}）")
    else:
        warn(f"C: 未在 SSE 捕获「连续失败」after（捕获={c_afters[:3] or '无'}；以日志为准）")
    return inject_c is not None


def run_phase_c() -> None:
    """G2 工具持续失败（单 run 内 3 次 exec 失败）→ 预算注入 + Timeline 文案。

    模型失败后自行换命令/路径属正常 ReAct 行为，单次驱动非确定性 → 最多 3 次尝试，
    任一出现注入即通过；仍失败则视为契约未达成（RESULTS 已有 FAIL 记录）。
    """
    print("\n[C] G2 工具持续失败（单 run 内 3 次 exec 失败）→ 预算注入 + Timeline 文案")
    for attempt in range(1, 4):
        print(f"  [C] attempt {attempt}/3")
        if _phase_c_attempt(attempt):
            return
    fail("C: 3 次尝试均未触发失败预算注入")


def main() -> int:
    phase_only = os.environ.get("GOAL_ALIGN_PHASE", "all")
    try:
        if phase_only == "c":
            print("[only-c] 开 tool-failure-budget（goal-check 强制 false）")
            set_switch("goal-check", False)
            set_switch("tool-failure-budget", True)
            run_phase_c()
            return finish()

        print("[A] 双开关关（线上默认）：简单单轮 → 零注入 + 无 tasks 步")
        offset_a = log_offset()
        token_a, conv_a = register_and_login("chat")
        reply_a = sse_text(chat_sse(token_a, conv_a, "你好"))
        print(f"  [A] reply_len={len(reply_a)}")
        time.sleep(3)
        log_a = log_since(offset_a)
        if any("[GoalAlignment]" in l or "[FailureBudget]" in l for l in log_a.splitlines()):
            fail("A: 开关关却出现注入日志")
        else:
            ok("A: 开关关无注入日志")
        if any("tasks" in l and "build" in l for l in log_a.splitlines()):
            warn("A: 简单单轮出现 tasks 步（观察，不阻断）")
        else:
            ok("A: 简单单轮无 tasks 步")

        print("\n[准备] 开 goal-check（every-n-think=3）→ 热切")
        set_switch("goal-check", True)
        ok("goal-check enabled=true 已同步")

        print("\n[B] G1 多步调研句：todo_write 建板 + 多轮工具 → goal-check 注入")
        offset_b = log_offset()
        token_b, conv_b = register_and_login("chat")
        query_b = (
            "请先使用 todo_write 为以下任务建立任务清单，然后按清单逐项完成："
            "1) 检索企业差旅报销制度的要点；2) 检索请假流程的要点；3) 输出两页要点对照。"
            "每完成一项请更新 todo 状态。")
        print(f"  [B] query={query_b[:40]}...")
        reply_b = sse_text(chat_sse(token_b, conv_b, query_b))
        print(f"  [B] reply_len={len(reply_b)}")
        inject_b = log_wait_since(offset_b, "[GoalAlignment] goal-check 注入")
        if inject_b:
            ok(f"B: goal-check 注入出现（{inject_b.strip()}）")
        else:
            # 首次回合 think 轮次可能不足，再驱动一轮延续任务
            warn("B: 首轮未注入，续发一轮再验证")
            offset_b2 = log_offset()
            sse_text(chat_sse(token_b, conv_b, "继续完成剩余子任务，并调用 rag 检索补充。"))
            inject_b2 = log_wait_since(offset_b2, "[GoalAlignment] goal-check 注入")
            if inject_b2:
                ok(f"B: 续轮 goal-check 注入出现（{inject_b2.strip()}）")
            else:
                fail("B: 两轮均未出现 [GoalAlignment] goal-check 注入日志")

        print("\n[准备] 关 goal-check、开 tool-failure-budget（same-signature-max=2）→ 热切")
        set_switch("goal-check", False)
        set_switch("tool-failure-budget", True)
        ok("tool-failure-budget enabled=true / goal-check false 已同步")

        run_phase_c()
    finally:
        print("\n[还原] 双开关回 false")
        for section in ("goal-check", "tool-failure-budget"):
            try:
                set_switch(section, False)
                ok(f"{section} 已还原 false")
            except Exception as exc:
                fail(f"还原 {section} 失败（需人工检查）：{exc}")

    return finish()


def finish() -> int:
    if RESULTS:
        print(f"\n❌ FAILED: {len(RESULTS)} 项未通过", file=sys.stderr)
        return 1
    print("\n✅ ALL PASSED: react-goal-alignment 4.7.7 Live 验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
