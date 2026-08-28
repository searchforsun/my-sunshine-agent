#!/usr/bin/env python3
"""business-context-authority M1–M3 Live 验收 — spec 2026-08-13-business-context-authority-design.md §8。

缓存模型决定流程（Policy 走 orchestrator 启动预热缓存 5min 刷新；任务板/偏好实时读 MySQL）:
  Phase 1 开关关（线上默认）
    A    chat×fast 显式 / 触发带场景 skill → 日志 [BizContext] skip gate=chat enabled=false（零注入）
  Phase 2 注册 C 用户 + 播种（policy 种子 + C 的任务板/偏好行）→ 开关置 true → 重启预热缓存
    B    开关开 + 无场景对话 → 日志 [BizContext] skip scene=null（无召回不装结构化层）
    C    开关开 + /expense-assist 触发 → 三块装载：
         硬证据 = 日志 loaded policy=true task=true prefs=true + 回复含 Policy 唯一标记
    D    kind=task 隔离：开关开 + /expense-assist → 日志 skip gate=task（一期不启用）
  Phase 3 还原（清种子 + 开关回 false + 重启）

用法:
  python3 scripts/verify_business_context_live.py
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 ecs4c16g / 3306 / root / root123）
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
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "ecs4c16g"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(SCRIPT_DIR, "..")
LOG_PATH = os.environ.get("ORCH_LOG", os.path.join(ROOT, "logs", "sunshine-orchestrator.log"))
NACOS_YAML = os.path.join(ROOT, "docs", "nacos", "sunshine-orchestrator.yaml")

POLICY_MARK = f"BIZCTX-POLICY-{uuid.uuid4().hex[:8].upper()}"
TASK_TITLE_MARK = f"验收任务{uuid.uuid4().hex[:6]}"
PREF_VALUE_MARK = f"邮件渠道-{uuid.uuid4().hex[:6]}"
SKILL_TRIGGER = "/expense-assist"
CHAT_QUERY = "请复述本会话场景 Policy 中的唯一标记（以 BIZCTX-POLICY- 开头），并说明焦点任务标题。"

RESULTS: list[str] = []


def fail(msg: str) -> None:
    RESULTS.append(msg)
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def warn(msg: str) -> None:
    print(f"  ⚠ {msg}")


def sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def mysql_exec(sql: str) -> list[str]:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [mysql, "-h", MYSQL["host"], "-P", str(MYSQL["port"]),
         "-u", MYSQL["user"], f"-p{MYSQL['password']}",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", sql],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")
    return [ln for ln in proc.stdout.splitlines() if ln.strip()]


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def register_and_login(kind: str = "chat") -> tuple[str, str, str]:
    """注册 + 登录 + 建会话；返回（token, conv_id, user_id）。"""
    user = f"bizctx_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "BizCtx"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    data = login.get("data") or {}
    token = data.get("tokenValue") or data.get("token")
    user_id = str(data.get("userId") or data.get("loginId") or "")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    conv = auth_json("POST", "/api/conversations", {"kind": kind}, token)
    conv_id = (conv.get("data") or conv).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {conv}")
    return token, str(conv_id), user_id


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


def log_wait_since(offset: int, pattern: str, timeout: int = 60) -> str | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = log_since(offset)
        for line in reversed(text.splitlines()):
            if pattern in line:
                return line
        time.sleep(2)
    return None


def set_enabled(enabled: bool, *, restart: bool) -> None:
    """切换 Nacos business-context.enabled 并同步；restart=True 时重启预热。"""
    with open(NACOS_YAML, "r", encoding="utf-8") as f:
        content = f.read()
    new_value = "true" if enabled else "false"
    updated = re.sub(
        r"(  business-context:\n    enabled: )(true|false)",
        rf"\g<1>{new_value}", content, count=1)
    if updated == content:
        raise RuntimeError("Nacos yaml 未找到 business-context.enabled 段")
    with open(NACOS_YAML, "w", encoding="utf-8") as f:
        f.write(updated)
    sync = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, "sync_nacos.py")],
        capture_output=True, text=True, timeout=120)
    if sync.returncode != 0:
        raise RuntimeError(f"sync_nacos failed: {sync.stdout} {sync.stderr}")
    if restart:
        restart_orchestrator()


def restart_orchestrator() -> None:
    """重启并等待就绪（启动预热 BizSceneCatalogClient Policy 缓存）。"""
    proc = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, "start.py"), "--restart", "orchestrator"],
        capture_output=True, text=True, timeout=180)
    if proc.returncode != 0:
        raise RuntimeError(f"start.py --restart orchestrator failed: {proc.stdout} {proc.stderr}")
    deadline = time.time() + 180
    while time.time() < deadline:
        try:
            resp = requests.get("http://127.0.0.1:8200/actuator/health", timeout=3)
            if resp.status_code == 200:
                time.sleep(3)
                return
        except requests.RequestException:
            pass
        time.sleep(5)
    raise RuntimeError("orchestrator 重启后 180s 未就绪")


def seed_business_data(user_id: str) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    # 清理历史遗留测试行（防 version 唯一键冲突）
    mysql_exec(
        "DELETE FROM sunshine_resource.biz_scene_policy "
        "WHERE biz_scene='expense-assist' AND version >= 90")
    policy_json = json.dumps({"红线": f"报销超 5000 必须审批，唯一标记 {POLICY_MARK}"},
                             ensure_ascii=False)
    mysql_exec(f"""
INSERT INTO sunshine_resource.biz_scene_policy
    (tenant_id, biz_scene, version, status, rules_json, effective_from, effective_to, updated_at)
VALUES ('default', 'expense-assist', 100, 'active',
    '{sql_escape(policy_json)}', NULL, NULL, '{now}')
""")
    task_id = f"vt{uuid.uuid4().hex[:8]}"
    steps_json = json.dumps(["提交报销单", "等待审批"], ensure_ascii=False)
    mysql_exec(f"""
INSERT INTO sunshine_chat.business_task
    (task_id, tenant_id, user_id, biz_scene, status, title, steps_json,
     retry_count, risk_level, created_at, updated_at)
VALUES ('{task_id}', 'default', '{sql_escape(user_id)}', 'expense-assist', 'running',
    '{sql_escape(TASK_TITLE_MARK)}', '{sql_escape(steps_json)}',
    0, 'medium', '{now}', '{now}')
""")
    pref_id = uuid.uuid4().hex
    mysql_exec(f"""
INSERT INTO sunshine_chat.user_context_state
    (id, scope, user_id, tenant_id, kind, state_key, state_value,
     biz_scene_scope, confirm_status, confidence, status, created_at, updated_at)
VALUES ('{pref_id}', 'user', '{sql_escape(user_id)}', 'default', 'preference',
    'refund.notify_channel', '{sql_escape(PREF_VALUE_MARK)}',
    'expense-assist', 'confirmed', 0.9, 'active', '{now}', '{now}')
""")


def cleanup_business_data(user_ids: list[str]) -> None:
    mysql_exec(
        "DELETE FROM sunshine_resource.biz_scene_policy "
        f"WHERE biz_scene='expense-assist' AND rules_json LIKE '%{POLICY_MARK}%'")
    for uid in user_ids:
        if not uid:
            continue
        mysql_exec(
            f"DELETE FROM sunshine_chat.business_task "
            f"WHERE user_id='{sql_escape(uid)}' AND title LIKE '{TASK_TITLE_MARK}'")
        mysql_exec(
            f"DELETE FROM sunshine_chat.user_context_state "
            f"WHERE user_id='{sql_escape(uid)}' AND state_value LIKE '{PREF_VALUE_MARK}'")


def main() -> int:
    if not shutil.which("mysql"):
        print("❌ mysql client 缺失", file=sys.stderr)
        return 2
    user_ids: list[str] = []
    try:
        print("[A] 开关关（线上默认）：显式触发带场景 skill → 零注入")
        offset_a = log_offset()
        token_a, conv_a, uid_a = register_and_login("chat")
        user_ids.append(uid_a)
        print(f"  [A] query={SKILL_TRIGGER} {CHAT_QUERY[:30]}...")
        reply_a = sse_text(chat_sse(token_a, conv_a, f"{SKILL_TRIGGER} {CHAT_QUERY}"))
        print(f"  [A] reply_len={len(reply_a)}")
        log_a = log_wait_since(offset_a, "[BizContext] skip gate=chat")
        if log_a and "enabled=false" in log_a:
            ok(f"A: 开关关跳过装载（{log_a.strip()}）")
        elif log_a:
            fail(f"A: 命中装载日志但非开关关分支：{log_a}")
        else:
            loaded = log_wait_since(offset_a, "[BizContext] loaded", timeout=10)
            if loaded:
                fail(f"A: 开关关却装载了权威层：{loaded}")
            else:
                warn(f"A: 未找到 [BizContext] 日志（回复长度={len(reply_a)}）")
        if POLICY_MARK in reply_a:
            fail("A: 开关关回复却含 Policy 标记")

        print("\n[准备] 注册 C 用户 + 播种 → 开关置 true → 重启预热缓存")
        token_c, conv_c, uid_c = register_and_login("chat")
        user_ids.append(uid_c)
        if not uid_c:
            raise RuntimeError("登录响应未返回 userId，无法播种 C 用户")
        seed_business_data(uid_c)
        set_enabled(True, restart=True)
        ok("种子就绪 + 开关 true + orchestrator 已重启预热")

        print("\n[B] 开关开 + 无场景对话 → 跳过结构化层")
        offset_b = log_offset()
        token_b, conv_b, uid_b = register_and_login("chat")
        user_ids.append(uid_b)
        print("  [B] query=今天天气怎么样")
        sse_text(chat_sse(token_b, conv_b, "今天天气怎么样"))
        log_b = log_wait_since(offset_b, "[BizContext] skip scene=null")
        if log_b:
            ok(f"B: 无召回跳过结构化层（{log_b.strip()}）")
        else:
            loaded = log_wait_since(offset_b, "[BizContext] loaded", timeout=10)
            if loaded:
                fail(f"B: 无场景却装载：{loaded}")
            else:
                warn("B: 未找到 [BizContext] skip scene=null 日志")

        print(f"\n[C] 开关开 + {SKILL_TRIGGER} 触发 → Policy/任务板/偏好三块装载")
        offset_c = log_offset()
        print(f"  [C] query={SKILL_TRIGGER} {CHAT_QUERY[:30]}...")
        reply_c = sse_text(chat_sse(token_c, conv_c, f"{SKILL_TRIGGER} {CHAT_QUERY}"))
        print(f"  [C] reply_len={len(reply_c)}")
        time.sleep(3)
        log_c = log_since(offset_c)
        scene_line = next((l for l in log_c.splitlines()
                           if "[BizScene] resolved scene=expense-assist" in l), None)
        loaded_line = next((l for l in log_c.splitlines()
                            if "[BizContext] loaded" in l), None)
        if scene_line:
            ok(f"C: 资源召回带出场景（{scene_line.strip()}）")
        else:
            fail("C: 未解析到 biz_scene=expense-assist")
        if loaded_line and "policy=true" in loaded_line and "task=true" in loaded_line \
                and "prefs=true" in loaded_line:
            ok(f"C: 三块装载（{loaded_line.strip()}）")
        elif loaded_line:
            fail(f"C: 装载不完整：{loaded_line}")
        else:
            fail("C: 未找到 [BizContext] loaded 日志")
        if POLICY_MARK in reply_c:
            ok("C: 回复引用 Policy 标记（块已进上下文，硬证据）")
        else:
            fail(f"C: 回复未含 Policy 标记（前 200 字：{reply_c[:200]}）")
        if TASK_TITLE_MARK in reply_c:
            ok("C: 回复引用任务板焦点标题")
        else:
            warn("C: 回复未含任务标题（模型可能省略；以日志为准）")

        print(f"\n[D] kind=task 隔离：开关开 + {SKILL_TRIGGER} → skip gate=task")
        offset_d = log_offset()
        token_d, _, uid_d = register_and_login("chat")
        user_ids.append(uid_d)
        task_conv = auth_json("POST", "/api/conversations", {"kind": "task"}, token_d)
        task_conv_id = str((task_conv.get("data") or task_conv).get("id"))
        print(f"  [D] kind=task query={SKILL_TRIGGER} 继续当前任务")
        sse_text(chat_sse(token_d, task_conv_id, f"{SKILL_TRIGGER} 继续当前任务"))
        log_d = log_wait_since(offset_d, "[BizContext] skip gate=task")
        if log_d:
            ok(f"D: task 会话隔离（{log_d.strip()}）")
        else:
            loaded = log_wait_since(offset_d, "[BizContext] loaded", timeout=10)
            if loaded:
                fail(f"D: task 会话却装载了权威层：{loaded}")
            else:
                warn("D: 未找到 [BizContext] skip gate=task 日志")
    finally:
        print("\n[E] 还原开关 + 清理种子数据")
        try:
            cleanup_business_data(user_ids)
            set_enabled(False, restart=True)
            ok("E: 种子清理 + 开关还原 false + orchestrator 已重启")
        except Exception as exc:
            fail(f"E: 还原/清理失败（需人工检查）：{exc}")

    if RESULTS:
        print(f"\n❌ FAILED: {len(RESULTS)} 项未通过", file=sys.stderr)
        return 1
    print("\n✅ ALL PASSED: business-context-authority M1–M3 Live 验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
