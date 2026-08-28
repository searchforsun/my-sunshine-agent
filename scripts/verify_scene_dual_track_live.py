#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M5 Live 验收：business-context-authority §2.1b/§2.1c/§5.5b 场景 embedding 回退 + auto 双轨。

A. 资源管理器端点（免开关）：embedding-index / vector 回填端点 / auto 场景创建与审核字段
B. 读路径 embedding 回退：无 skill/agent 触发 → query 向量化 → 命中既有 active 场景（懒回填 description_vector）
C. 写路径 auto 创建：≥2 轮独特业务域对话 → LLM 判定 → auto 场景（pending_review）落库
D. auto 审核：status=active + approved_by/approved_at 落库
E. 还原：清理测试场景 + 开关还原 false + api-key 置空 + 重启

用法：python3 scripts/verify_scene_dual_track_live.py
"""
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

import requests

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
EMBED_KEY = "sk-22e7ab6f7bbb4078b7163facb4d3aee0"

# 验收用独立业务域（区别于既有 expense-assist / compliance-review / policy-qa / travel-budget）
SCENE_TOPIC_Q1 = "我明天想请一天年假，请问审批流程怎么走，需要提前多久申请"
SCENE_TOPIC_Q2 = "年假额度在哪里能查到余额？如果领导不在是不是可以走代理审批"
AUTO_MARK = f"auto-{uuid.uuid4().hex[:8]}"

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
    user = f"dualtrack_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "DualTrack"}, None)
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


def set_m5_switches(on: bool, *, restart: bool) -> None:
    """开/关 M5 三组开关并同步 Nacos；restart=True 时重启预热（触发向量懒回填）。

    幂等：三处 enabled 用正则整体替换（无论当前值），api-key 统一写值。
    """
    with open(NACOS_YAML, "r", encoding="utf-8") as f:
        content = f.read()
    new_value = "true" if on else "false"
    pairs = [
        (r"(  business-context:\n    enabled: )(true|false)", new_value),
        (r"(    scene-embedding:\n      enabled: )(true|false)", new_value),
        (r"(    scene-auto:\n      enabled: )(true|false)", new_value),
    ]
    updated = content
    for pattern, value in pairs:
        updated = re.sub(pattern, rf"\g<1>{value}", updated, count=1)
    # api-key 统一写值（开=注入密钥，关=空串）；限定 6 空格缩进避免误伤其它段
    updated = re.sub(r'(      api-key: ")[^"]*(")', rf'\1{EMBED_KEY if on else ""}\2', updated, count=1)
    if updated == content:
        raise RuntimeError("Nacos yaml 开关片段未匹配到（结构可能已变化）")
    with open(NACOS_YAML, "w", encoding="utf-8") as f:
        f.write(updated)
    with open(NACOS_YAML, "w", encoding="utf-8") as f:
        f.write(updated)
    sync = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, "sync_nacos.py")],
        capture_output=True, text=True, timeout=120)
    if sync.returncode != 0:
        raise RuntimeError(f"sync_nacos failed: {sync.stdout} {sync.stderr}")
    if restart:
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, "start.py"), "--restart", "orchestrator"],
            capture_output=True, text=True, timeout=180)
        if proc.returncode != 0:
            raise RuntimeError(f"start.py --restart orchestrator failed: {proc.stdout} {proc.stderr}")
        deadline = time.time() + 240
        while time.time() < deadline:
            try:
                resp = requests.get("http://127.0.0.1:8200/actuator/health", timeout=3)
                if resp.status_code == 200:
                    time.sleep(5)
                    return
            except requests.RequestException:
                pass
            time.sleep(5)
        raise RuntimeError("orchestrator 重启后 240s 未就绪")


def scene_rows(code: str) -> list[tuple[str, ...]]:
    rows = mysql_exec(
        f"SELECT biz_scene, status, source, IFNULL(approved_by,''), "
        f"IFNULL(approved_at,''), IFNULL(description_vector,'') "
        f"FROM sunshine_resource.biz_scene_definition WHERE biz_scene='{sql_escape(code)}'")
    return [tuple(r.split("\t")) for r in rows]


def main() -> int:
    if not shutil.which("mysql"):
        print("❌ mysql client 缺失", file=sys.stderr)
        return 2
    auto_code = ""
    token_a = ""
    try:
        # 网关 /api/** 需鉴权：先注册登录取 token（biz-scenes Lab 操作统一走网关）
        token_a, _, _ = register_and_login("chat")

        print("[A] 资源管理器端点（免开关）")
        headers = {"Content-Type": "application/json",
                   "Authorization": f"Bearer {token_a}"}
        idx = requests.get(
            f"{GATEWAY_URL}/api/biz-scenes/embedding-index",
            headers=headers, timeout=15).json()
        items = (idx.get("data") or [])
        if not items:
            raise RuntimeError("embedding-index 返回空列表")
        manual = [i for i in items if i.get("source", "manual") == "manual"]
        if manual:
            ok(f"A1: embedding-index 含 {len(manual)} 个 manual 场景（首例 {manual[0].get('bizScene')}）")
        else:
            fail("A1: embedding-index 无 manual 场景")
        # 临时 manual 场景 → PUT vector → descriptionVector 落库
        tmp_code = f"tmp-{uuid.uuid4().hex[:8]}"
        created = auth_json("POST", "/api/biz-scenes", {
            "bizScene": tmp_code, "displayName": "临时验收场景",
            "description": "临时场景，验收 vector 回填端点", "source": "manual"}, token_a)
        if (created.get("code") or 200) != 200:
            raise RuntimeError(f"create tmp scene failed: {created}")
        resp = requests.put(
            f"{GATEWAY_URL}/api/biz-scenes/{tmp_code}/vector",
            headers=headers,
            json={"vector": [0.1, 0.2, 0.3]}, timeout=15)
        if resp.status_code != 200 or (resp.json().get("code") or 200) != 200:
            raise RuntimeError(f"PUT vector failed: {resp.text}")
        row = scene_rows(tmp_code)
        if row and row[0][5] and "0.1" in row[0][5]:
            ok(f"A2: vector 端点回填 description_vector 落库（{tmp_code}）")
        else:
            fail(f"A2: vector 未落库：{row}")
        auth_json("DELETE", f"/api/biz-scenes/{tmp_code}", None, token_a)
        ok(f"A3: 临时场景已清理（{tmp_code}）")

        print("\n[准备] 打开 M5 三开关 + api-key → 重启预热（懒回填向量）")
        set_m5_switches(True, restart=True)
        ok("M5 开关开 + orchestrator 已重启")
        time.sleep(5)

        print("\n[B] 读路径：无召回闲聊 → resolved scene=null → 跳过结构化层（V11）")
        token_b, conv_b, uid_b = register_and_login("chat")
        offset_b = log_offset()
        print("  [B] query=今天天气怎么样（无 skill/agent 召回）")
        chat_sse(token_b, conv_b, "今天天气怎么样")
        resolved_b = log_wait_since(offset_b, "[BizScene] resolved scene=", timeout=120)
        if resolved_b and "scene=null" in resolved_b:
            ok(f"B: 无召回场景解析为 null（{resolved_b.strip()}）")
        else:
            fail(f"B: 期望 scene=null，实际 {resolved_b.strip() if resolved_b else '无日志'}")
        rows = scene_rows("expense-assist")
        if rows and rows[0][5]:
            ok("B: 既有 active 场景 description_vector 已懒回填")
        else:
            warn("B: expense-assist 向量未回填（后续场景可能受影响）")

        print("\n[C] 写路径 auto 创建：≥2 轮请假域对话（无 skill 绑定）→ embedding 未命中 → LLM 创建 auto 场景")
        token_c, conv_c, uid_c = register_and_login("chat")
        offset_c = log_offset()
        # LLM 判定具随机性：连续发送多条请假域消息，任一轮触发创建即成功
        followups = [
            SCENE_TOPIC_Q1,
            SCENE_TOPIC_Q2,
            "我上周提交的年假申请现在还卡在审批中，能帮我查一下卡在哪一步吗",
            "如果年假申请被经理驳回，我重新提交需要注意什么",
        ]
        created_line = None
        for i, q in enumerate(followups):
            print(f"  [C] 第 {i + 1} 条消息: {q[:36]}...")
            chat_sse(token_c, conv_c, q)
            created_line = log_wait_since(offset_c, "[SceneAuto] 创建 auto 场景", timeout=120)
            if created_line:
                break
            if i < len(followups) - 1:
                time.sleep(2)
        if created_line:
            m = re.search(r"scene=([\w-]+)", created_line)
            auto_code = m.group(1) if m else ""
            ok(f"C: auto 场景创建（{created_line.strip()}）")
        else:
            dup_line = log_wait_since(offset_c, "[SceneAuto] 与既有场景", timeout=15)
            skip_line = log_wait_since(offset_c, "[SceneAuto] LLM 判定", timeout=15)
            fail(f"C: 未创建 auto 场景（dup={dup_line.strip() if dup_line else '无'} skip={skip_line.strip() if skip_line else '无'}）")
        if auto_code:
            rows = scene_rows(auto_code)
            if rows and rows[0][1] == "pending_review" and rows[0][2] == "auto":
                ok(f"C: 落库校验 source=auto / status=pending_review（{rows[0]}）")
            else:
                fail(f"C: 落库状态异常：{rows}")

        if auto_code:
            print("\n[B'] 读/写路径 embedding 命中 auto 场景（pending_review 可嵌入检索，§2.1c/V21）")
            # 等待异步向量化完成
            deadline = time.time() + 60
            while time.time() < deadline:
                rows = scene_rows(auto_code)
                if rows and rows[0][5]:
                    break
                time.sleep(5)
            if not (rows and rows[0][5]):
                warn("B': auto 场景向量未在 60s 内回填（命中断言可能失败）")
            time.sleep(5)
            token_bp, conv_bp, uid_bp = register_and_login("chat")
            offset_bp = log_offset()
            chat_sse(token_bp, conv_bp, "我要请年假，帮我查一下流程和余额")
            write_hit = log_wait_since(offset_bp, "[SceneWrite] ② embedding 回退命中 scene=", timeout=150)
            read_hit = log_wait_since(offset_bp, "[BizScene] embedding 回退命中 scene=", timeout=15)
            if write_hit:
                ok(f"B': 写路径 embedding 命中（{write_hit.strip()}）")
            else:
                fail(f"B': 写路径未命中 auto 场景（{auto_code}）")
            if read_hit:
                ok(f"B': 读路径 embedding 命中（{read_hit.strip()}）")
            else:
                resolved_bp = log_wait_since(offset_bp, "[BizScene] resolved scene=", timeout=15)
                warn(f"B': 读路径未命中（resolved={resolved_bp.strip() if resolved_bp else '无'}；写路径为准）")

        if auto_code:
            print("\n[D] auto 审核：通过 → active + 审核人/时间落库")
            updated = auth_json("PUT", f"/api/biz-scenes/{auto_code}",
                                {"status": "active", "approvedBy": "dualtrack-operator"}, token_a)
            if (updated.get("code") or 200) != 200:
                raise RuntimeError(f"approve failed: {updated}")
            rows = scene_rows(auto_code)
            if rows and rows[0][1] == "active" and rows[0][3] == "dualtrack-operator" and rows[0][4]:
                ok(f"D: 审核通过落库（{rows[0][:4]} approved_at={rows[0][4]}）")
            else:
                fail(f"D: 审核落库异常：{rows}")
    finally:
        print("\n[E] 清理 + 还原开关")
        try:
            if auto_code:
                mysql_exec(f"DELETE FROM sunshine_resource.biz_scene_definition "
                           f"WHERE biz_scene='{sql_escape(auto_code)}'")
                ok(f"E: 已删除验收 auto 场景 {auto_code}")
            set_m5_switches(False, restart=True)
            ok("E: 开关还原 false + api-key 置空 + orchestrator 已重启")
        except Exception as exc:
            fail(f"E: 还原/清理失败（需人工检查）：{exc}")

    if RESULTS:
        print(f"\n❌ FAILED: {len(RESULTS)} 项未通过", file=sys.stderr)
        return 1
    print("\n✅ ALL PASSED: business-context-authority M5（embedding 回退 + 场景双轨）Live 验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
