#!/usr/bin/env python3
"""4.13 Workflow Studio Live — workflow-manager DB Catalog + published API + Studio + # 路由。

用法:
  python3 scripts/verify_workflow_studio_live.py --suite catalog
  python3 scripts/verify_workflow_studio_live.py --suite studio
  python3 scripts/verify_workflow_studio_live.py --suite hash
  python3 scripts/verify_workflow_studio_live.py --suite all

环境变量:
  GATEWAY_URL            BFF 网关，默认 http://127.0.0.1:8000
  WORKFLOW_MANAGER_URL   直连 workflow-manager，默认 http://127.0.0.1:8230
  RAG_URL                hash 套件 RAG 预检，默认 http://127.0.0.1:8400

前置:
  - MySQL init 已执行（13-sunshine-workflow-manager.sql 含 11 标杆种子）
  - workflow-manager :8230、gateway :8000、orchestrator :8200 已启动
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import uuid

import requests

from sunshine_lib import ROOT, unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
WM_URL = os.environ.get("WORKFLOW_MANAGER_URL", "http://127.0.0.1:8230").rstrip("/")
RAG_URL = os.environ.get("RAG_URL", "http://127.0.0.1:8400").rstrip("/")
HASH_TIMEOUT_SEC = int(os.environ.get("WORKFLOW_HASH_TIMEOUT_SEC", "120"))

SEED_IDS = {
    "knowledge-qa",
    "finance-list",
    "finance-smart",
    "finance-summary",
    "knowledge-dual",
    "knowledge-branch",
    "knowledge-loop",
    "sandbox-agent",
    "hr-leave-assist",
    "expense-compliance",
    "oa-task-assist",
}


def wm_json(path: str) -> list | dict:
    resp = requests.get(f"{WM_URL}{path}", timeout=30)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def bff_json(path: str, headers: dict) -> list | dict:
    resp = requests.get(f"{GATEWAY_URL}{path}", headers=headers, timeout=30)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def bff_post(path: str, headers: dict, body: dict) -> list | dict:
    resp = requests.post(
        f"{GATEWAY_URL}{path}",
        headers={**headers, "Content-Type": "application/json"},
        json=body,
        timeout=30,
    )
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def bff_put(path: str, headers: dict, body: dict) -> list | dict | None:
    resp = requests.put(
        f"{GATEWAY_URL}{path}",
        headers={**headers, "Content-Type": "application/json"},
        json=body,
        timeout=30,
    )
    resp.raise_for_status()
    if resp.status_code == 204 or not resp.text.strip():
        return None
    return unwrap_r(resp.json(), context=path)


def auth_headers() -> dict[str, str]:
    username = f"wf_studio_{uuid.uuid4().hex[:8]}"
    password = "password123"
    reg = requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": username, "password": password},
        timeout=30,
    )
    reg.raise_for_status()
    if reg.json().get("code") != 200:
        raise RuntimeError(f"register failed: {reg.json()}")
    login = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.json()}")
    return {"Authorization": f"Bearer {token}"}


def auth_json(method: str, path: str, body: dict | None, token: str) -> dict:
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def conversation_id(response: dict) -> str:
    if response.get("code") == 200 and response.get("data", {}).get("id"):
        return response["data"]["id"]
    if response.get("id"):
        return response["id"]
    raise RuntimeError(f"create conversation failed: {response}")


def chat_sse(token: str, conv_id: str, query: str, **extra) -> str:
    import shutil
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found (required for SSE sampling)")
    payload = {"content": query, "conversationId": conv_id, **extra}
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        json.dump(payload, f, ensure_ascii=False)
        tmp = f.name
    try:
        proc = subprocess.run(
            [
                curl, "-N", "-s", "-m", str(HASH_TIMEOUT_SEC),
                "-X", "POST", f"{GATEWAY_URL}/api/chat/stream",
                "-H", f"Authorization: Bearer {token}",
                "-H", "Content-Type: application/json",
                "-d", f"@{tmp}",
            ],
            capture_output=True,
            text=True,
            timeout=HASH_TIMEOUT_SEC + 10,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"curl SSE failed rc={proc.returncode}: {proc.stderr[:500]}")
        return proc.stdout
    finally:
        os.unlink(tmp)


def chat_sse_async(token: str, conv_id: str, query: str, **extra) -> subprocess.Popen:
    import shutil
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found (required for SSE sampling)")
    payload = {"content": query, "conversationId": conv_id, **extra}
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        json.dump(payload, f, ensure_ascii=False)
        tmp = f.name
    proc = subprocess.Popen(
        [
            curl, "-N", "-s", "-m", str(HASH_TIMEOUT_SEC),
            "-X", "POST", f"{GATEWAY_URL}/api/chat/stream",
            "-H", f"Authorization: Bearer {token}",
            "-H", "Content-Type: application/json",
            "-d", f"@{tmp}",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    proc._payload_tmp = tmp  # type: ignore[attr-defined]
    return proc


def cleanup_sse_proc(proc: subprocess.Popen | None) -> None:
    if proc is None:
        return
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    tmp = getattr(proc, "_payload_tmp", None)
    if tmp and os.path.exists(tmp):
        os.unlink(tmp)


def wait_workflow_routed(token: str, conv_id: str, expected_id: str, max_wait_sec: int = 45) -> dict:
    deadline = time.time() + max_wait_sec
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and workflow_hit(assistants[-1], expected_id):
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"路由未命中 {expected_id}（{max_wait_sec}s 内）")


def wait_assistant(token: str, conv_id: str, max_wait_sec: int = 90) -> dict:
    deadline = time.time() + max_wait_sec
    while time.time() < deadline:
        detail = auth_json("GET", f"/api/conversations/{conv_id}", None, token)
        messages = detail.get("messages") or detail.get("data", {}).get("messages") or []
        assistants = [m for m in messages if m.get("role") == "assistant"]
        if assistants and assistants[-1].get("status") in ("completed", "failed", "interrupted"):
            return assistants[-1]
        time.sleep(2)
    raise RuntimeError(f"assistant not completed within {max_wait_sec}s")


def refresh_orchestrator_catalog() -> None:
    """发布 workflow 后 orchestrator 经 Redis 刷新 catalog；验收脚本兜底重启。"""
    import subprocess
    print("  [INFO] 重启 orchestrator 以同步 workflow catalog …")
    proc = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "start.py"), "--restart", "orchestrator"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        timeout=180,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"orchestrator 重启失败: {proc.stderr[-500:]}")
    time.sleep(12)


def suite_catalog() -> None:
    print("[catalog] workflow-manager direct")
    catalog = wm_json("/api/workflows/catalog")
    if not isinstance(catalog, list) or len(catalog) < 4:
        raise RuntimeError(f"catalog 条目不足: {catalog}")
    ids = {e.get("id") for e in catalog if isinstance(e, dict)}
    missing = SEED_IDS - ids
    if missing:
        raise RuntimeError(f"缺少种子 workflow: {missing}")
    print(f"  [OK] GET /api/workflows/catalog -> {len(catalog)} entries")
    for wf_id in sorted(SEED_IDS):
        pub = wm_json(f"/api/workflows/{wf_id}/published")
        plan = pub.get("plan") if isinstance(pub, dict) else None
        nodes = (plan or {}).get("nodes") if isinstance(plan, dict) else None
        if not nodes:
            raise RuntimeError(f"{wf_id} published plan 无 nodes")
        types = {n.get("type") for n in nodes if isinstance(n, dict)}
        if "answer" not in types:
            raise RuntimeError(f"{wf_id} plan 缺少 answer 节点")
        print(f"  [OK] GET /api/workflows/{wf_id}/published -> nodes={len(nodes)}")
    editable = wm_json("/api/workflows/knowledge-qa/editable")
    if not isinstance(editable, dict) or not editable.get("plan"):
        raise RuntimeError("editable 接口异常")
    print("  [OK] GET /api/workflows/knowledge-qa/editable")


def suite_bff() -> None:
    print("[bff] Gateway 透传")
    headers = auth_headers()
    catalog = bff_json("/api/workflows/catalog", headers)
    if not isinstance(catalog, list) or not catalog:
        raise RuntimeError("BFF catalog 为空")
    print(f"  [OK] BFF GET /api/workflows/catalog -> {len(catalog)} entries")
    workflows = bff_json("/api/workflows", headers)
    if not isinstance(workflows, list) or not workflows:
        raise RuntimeError("BFF list 为空")
    print(f"  [OK] BFF GET /api/workflows -> {len(workflows)} items")


def suite_studio() -> str:
    print("[studio] 新建 → 草稿 → 发布")
    headers = auth_headers()
    wf_id = f"live-studio-{uuid.uuid4().hex[:8]}"
    created = bff_post("/api/workflows", headers, {
        "id": wf_id,
        "displayName": "Live Studio 验收",
        "description": "verify_workflow_studio_live studio suite 路由描述",
    })
    if not isinstance(created, dict) or created.get("id") != wf_id:
        raise RuntimeError(f"create 失败: {created}")
    print(f"  [OK] POST /api/workflows -> {wf_id}")
    plan = {
        "planId": None,
        "reason": f"Live studio {wf_id}",
        "nodes": [
            {"id": "start", "type": "start", "displayName": "开始", "params": {}},
            {"id": "rag-a1b2c3d4", "type": "rag", "displayName": "知识检索", "params": {
                "query": "{{start.userQuery}}",
                "topK": "2",
                "retry.maxAttempts": "1",
                "retry.backoffMs": "500",
                "retry.onFailure": "continue",
            }},
            {"id": "answer", "type": "answer", "displayName": "生成回答", "params": {
                "prompt": "根据检索结果回答。\n\n{{rag-a1b2c3d4.output}}",
                "retry.maxAttempts": "2",
                "retry.backoffMs": "500",
                "retry.onFailure": "fail_fast",
            }},
        ],
        "edges": [
            {"from": "start", "to": "rag-a1b2c3d4"},
            {"from": "rag-a1b2c3d4", "to": "answer"},
        ],
    }
    catalog_meta = {"examples": [f"{wf_id} 测试"], "nodeSummary": ["start", "rag", "answer"]}
    bff_put(f"/api/workflows/{wf_id}/draft", headers, {"plan": plan, "catalog": catalog_meta})
    print("  [OK] PUT /api/workflows/{id}/draft")
    published = bff_post(f"/api/workflows/{wf_id}/publish", headers, {})
    if not isinstance(published, dict) or not published.get("plan"):
        raise RuntimeError(f"publish 失败: {published}")
    print("  [OK] POST /api/workflows/{id}/publish")
    cat = bff_json("/api/workflows/catalog", headers)
    ids = {e.get("id") for e in cat if isinstance(e, dict)} if isinstance(cat, list) else set()
    if wf_id not in ids:
        raise RuntimeError(f"发布后 catalog 未包含 {wf_id}")
    print(f"  [OK] catalog 含新 workflow ({len(cat)} entries)")
    refresh_orchestrator_catalog()
    return wf_id


def preflight_rag() -> None:
    resp = requests.post(
        f"{RAG_URL}/api/rag/search",
        json={"query": "青松假有多少天、怎么申请", "topK": 3},
        timeout=30,
    )
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="rag preflight") or {}
    if not data.get("results"):
        raise RuntimeError("RAG 预检 0 命中，hash 套件跳过或先 rag_ingest_bulk")


def workflow_hit(assistant: dict, expected_id: str) -> bool:
    wf = assistant.get("workflowId")
    intent = str(assistant.get("intent") or "")
    if wf == expected_id:
        return True
    return expected_id in intent and intent.startswith("workflow")


def suite_hash(wf_id: str | None = None) -> None:
    print("[hash] #workflow L0 路由 Live")
    preflight_rag()
    token_hdr = auth_headers()
    token = token_hdr["Authorization"].removeprefix("Bearer ").strip()
    conv_id = conversation_id(auth_json("POST", "/api/conversations", None, token))

    query = "#knowledge-qa 青松假有多少天、怎么申请"
    print(f"  query={query}")
    chat_sse(token, conv_id, query, executionMode="workflow")
    assistant = wait_assistant(token, conv_id, HASH_TIMEOUT_SEC)
    wf = assistant.get("workflowId")
    intent = assistant.get("intent")
    steps = assistant.get("steps") or []
    steps_json = json.dumps(steps, ensure_ascii=False)
    has_plan = "node-rag-c5d7e903" in steps_json or "node-rag" in steps_json or "plan" in steps_json
    ok_seed = workflow_hit(assistant, "knowledge-qa") and has_plan
    if not ok_seed:
        raise RuntimeError(
            f"I1 失败 workflowId={wf} intent={intent} has_plan={has_plan}",
        )
    print(f"  [OK] #knowledge-qa -> workflowId={wf} intent={intent}")

    if wf_id:
        time.sleep(3)
        conv2 = conversation_id(auth_json("POST", "/api/conversations", None, token))
        q2 = f"#{wf_id} 测试路由"
        print(f"  query={q2}")
        proc = chat_sse_async(token, conv2, q2, executionMode="workflow")
        try:
            assistant2 = wait_workflow_routed(token, conv2, wf_id, 60)
            print(
                f"  [OK] #{wf_id} Studio 发布后 L0 命中 "
                f"(intent={assistant2.get('intent')})",
            )
        finally:
            cleanup_sse_proc(proc)


def suite_parallel() -> None:
    print("[parallel] 4.7.2 双 RAG fan-out/join Live")
    preflight_rag()
    token_hdr = auth_headers()
    token = token_hdr["Authorization"].removeprefix("Bearer ").strip()
    conv_id = conversation_id(auth_json("POST", "/api/conversations", None, token))
    query = "#knowledge-dual 青松假和网约车报销上限一起查"
    print(f"  query={query}")
    chat_sse(token, conv_id, query, executionMode="workflow")
    assistant = wait_assistant(token, conv_id, HASH_TIMEOUT_SEC)
    if not workflow_hit(assistant, "knowledge-dual"):
        raise RuntimeError(
            f"parallel 路由失败 workflowId={assistant.get('workflowId')} "
            f"intent={assistant.get('intent')}",
        )
    steps_json = json.dumps(assistant.get("steps") or [], ensure_ascii=False)
    has_policy = "node-rag-a1b2c3d4" in steps_json
    has_finance = "node-rag-e5f6a7b8" in steps_json
    has_join = "node-join-c9d0e1f2" in steps_json
    if not (has_policy and has_finance and has_join):
        raise RuntimeError(
            f"parallel 步骤缺失 policy={has_policy} finance={has_finance} join={has_join}",
        )
    print(
        f"  [OK] #knowledge-dual 并行步骤 "
        f"(rag-a1b2c3d4 + rag-e5f6a7b8 + join-c9d0e1f2)",
    )


def suite_exclusive() -> None:
    print("[exclusive] 4.13.7 exclusive-gateway 边条件 Live（OR 多条件）")
    preflight_rag()
    token_hdr = auth_headers()
    token = token_hdr["Authorization"].removeprefix("Bearer ").strip()

    # 多条件语义（knowledge-branch 升级后）：
    #   condition.logic = or
    #   condition.items = [
    #     { start.userQuery contains "报销" },
    #     { start.userQuery contains "发票" },
    #   ]
    # 含「报销」或「发票」均命中财务分支 rag-f1a2b3c4；都不含走 default rag-d5e6f7a8。
    #
    # q1（含「报销」）：OR 第一条命中 → 财务 RAG
    conv1 = conversation_id(auth_json("POST", "/api/conversations", None, token))
    q1 = "#knowledge-branch 网约车报销需要哪些材料"
    print(f"  query={q1}")
    chat_sse(token, conv1, q1, executionMode="workflow")
    a1 = wait_assistant(token, conv1, HASH_TIMEOUT_SEC)
    if not workflow_hit(a1, "knowledge-branch"):
        raise RuntimeError(
            f"exclusive 条件路由失败 workflowId={a1.get('workflowId')} intent={a1.get('intent')}",
        )
    s1 = json.dumps(a1.get("steps") or [], ensure_ascii=False)
    if "node-rag-f1a2b3c4" not in s1:
        raise RuntimeError(f"条件分支未走财务 RAG: steps={s1[:800]}")
    if "node-rag-d5e6f7a8" in s1:
        raise RuntimeError("条件命中时不应执行默认人事 RAG")
    print("  [OK] 含「报销」→ OR 第一条命中 → node-rag-f1a2b3c4")

    # q2（都不含）：OR 两条均不命中 -> 走 default 人事 RAG
    conv2 = conversation_id(auth_json("POST", "/api/conversations", None, token))
    q2 = "#knowledge-branch 青松假怎么申请"
    print(f"  query={q2}")
    chat_sse(token, conv2, q2, executionMode="workflow")
    a2 = wait_assistant(token, conv2, HASH_TIMEOUT_SEC)
    if not workflow_hit(a2, "knowledge-branch"):
        raise RuntimeError(
            f"exclusive 默认路由失败 workflowId={a2.get('workflowId')} intent={a2.get('intent')}",
        )
    s2 = json.dumps(a2.get("steps") or [], ensure_ascii=False)
    if "node-rag-d5e6f7a8" not in s2:
        raise RuntimeError(f"默认分支未走人事 RAG: steps={s2[:800]}")
    if "node-rag-f1a2b3c4" in s2:
        raise RuntimeError("默认分支不应执行财务 RAG")
    print("  [OK] 都不含 → OR 不命中 → default node-rag-d5e6f7a8")

    # q3（含「发票」）：OR 第二条命中 → 财务 RAG
    conv3 = conversation_id(auth_json("POST", "/api/conversations", None, token))
    q3 = "#knowledge-branch 发票申请流程"
    print(f"  query={q3}")
    chat_sse(token, conv3, q3, executionMode="workflow")
    a3 = wait_assistant(token, conv3, max(HASH_TIMEOUT_SEC, 180))
    if not workflow_hit(a3, "knowledge-branch"):
        raise RuntimeError(
            f"exclusive OR 路由失败 workflowId={a3.get('workflowId')} intent={a3.get('intent')}",
        )
    s3 = json.dumps(a3.get("steps") or [], ensure_ascii=False)
    if "node-rag-f1a2b3c4" not in s3:
        raise RuntimeError(f"OR 第二条命中未走财务 RAG: steps={s3[:800]}")
    if "node-rag-d5e6f7a8" in s3:
        raise RuntimeError("OR 命中时不应执行默认人事 RAG")
    print("  [OK] 含「发票」→ OR 第二条命中 → node-rag-f1a2b3c4")


def _as_steps(assistant: dict) -> list[dict]:
    steps = assistant.get("steps")
    if isinstance(steps, str):
        try:
            steps = json.loads(steps) if steps.strip() else []
        except json.JSONDecodeError:
            return []
    if not isinstance(steps, list):
        return []
    return [s for s in steps if isinstance(s, dict)]


def _top_step_ids(assistant: dict) -> list[str]:
    return [str(s.get("id") or "") for s in _as_steps(assistant)]


def _find_loop_step(assistant: dict) -> dict | None:
    for s in _as_steps(assistant):
        if str(s.get("id") or "").startswith("node-loop-"):
            return s
    return None


def suite_loop() -> None:
    print("[loop] 4.13.7 loop do-while + subSteps Live（AND 多条件）")
    preflight_rag()
    token_hdr = auth_headers()
    token = token_hdr["Authorization"].removeprefix("Bearer ").strip()

    # 多条件语义（knowledge-loop 升级后）：
    #   conditions = [
    #     { rag-l1o2o3p4.output contains "继续" },
    #     { tool-t1o2o3p4.output not_contains "已完成" },
    #   ]
    #   conditionLogic = and
    # 即：检索输出含「继续」且待报销未「已完成」才继续下一轮。
    #
    # q1（无「继续」）：rag 输出不含「继续」→ AND 第一条不满足 → 条件不满足 → 仅 i1 一轮退出
    conv1 = conversation_id(auth_json("POST", "/api/conversations", None, token))
    q1 = "#knowledge-loop 分析青松假余额和我的待报销"
    print(f"  query={q1}")
    chat_sse(token, conv1, q1, executionMode="workflow")
    a1 = wait_assistant(token, conv1, max(HASH_TIMEOUT_SEC, 180))
    if not workflow_hit(a1, "knowledge-loop"):
        raise RuntimeError(
            f"loop 路由失败 workflowId={a1.get('workflowId')} intent={a1.get('intent')}",
        )
    top1 = _top_step_ids(a1)
    if "node-loop-a1b2c3d4" not in top1:
        raise RuntimeError(f"主时间线缺少 loop 步: {top1}")
    for body_id in ("node-rag-l1o2o3p4", "node-tool-t1o2o3p4", "node-tool-leave01", "node-agent-a1g2e3n4"):
        if body_id in top1:
            raise RuntimeError(f"body 不应出现在主时间线: {body_id} in {top1}")
    loop1 = _find_loop_step(a1)
    if not loop1:
        raise RuntimeError("未找到 node-loop-* 步骤")
    after1 = ((loop1.get("summary") or {}).get("after") or "")
    if "未进入循环体" in after1:
        raise RuntimeError(f"do-while 应至少 1 轮: after={after1}")
    sub1 = [str(s.get("id") or "") for s in (loop1.get("subSteps") or []) if isinstance(s, dict)]
    for body in ("node-rag-l1o2o3p4", "node-tool-t1o2o3p4", "node-tool-leave01", "node-agent-a1g2e3n4"):
        if f"i1-{body}" not in sub1:
            raise RuntimeError(f"首轮缺少 {body}: {sub1}")
        if f"i2-{body}" in sub1:
            raise RuntimeError(f"无「继续」不应有第 2 轮: {sub1}")
    print("  [OK] 无「继续」→ AND 第一条不满足 → 仅 i1 一轮")

    # q2（检索含「继续」）：查询「继续出差违规处理」使 rag 命中《跨制度场景处理速查》§8
    # 禁止事项 chunk（含「禁止隐瞒病情继续挂出差状态...」），故 rag 输出 contains "继续"（AND 第一条满足）；
    # tool 输出「未查询到符合条件的报销单。」不含「已完成」（AND 第二条满足）
    # → 条件满足 → 继续 i2 → i2 后达 maxIterations=2 → exit
    conv2 = conversation_id(auth_json("POST", "/api/conversations", None, token))
    q2 = "#knowledge-loop 继续出差违规处理和待报销"
    print(f"  query={q2}")
    chat_sse(token, conv2, q2, executionMode="workflow")
    a2 = wait_assistant(token, conv2, max(HASH_TIMEOUT_SEC, 180))
    if not workflow_hit(a2, "knowledge-loop"):
        raise RuntimeError(
            f"loop 多轮路由失败 workflowId={a2.get('workflowId')} intent={a2.get('intent')}",
        )
    loop2 = _find_loop_step(a2)
    if not loop2:
        raise RuntimeError("多轮未找到 node-loop-* 步骤")
    sub2 = [str(s.get("id") or "") for s in (loop2.get("subSteps") or []) if isinstance(s, dict)]
    for round_prefix in ("i1-", "i2-"):
        for body in ("node-rag-l1o2o3p4", "node-tool-t1o2o3p4", "node-tool-leave01", "node-agent-a1g2e3n4"):
            expect = round_prefix + body
            if expect not in sub2:
                raise RuntimeError(f"缺少 subStep {expect}: {sub2}")
    print("  [OK] 含「继续」→ AND 两条均满足 → i1/i2 rag+tool+agent")


def main() -> int:
    parser = argparse.ArgumentParser(description="Workflow Studio Live 验收")
    parser.add_argument(
        "--suite",
        choices=["catalog", "bff", "studio", "hash", "parallel", "exclusive", "loop", "all"],
        default="catalog",
    )
    args = parser.parse_args()
    studio_id: str | None = None
    try:
        if args.suite in ("catalog", "all"):
            suite_catalog()
        if args.suite in ("bff", "all"):
            suite_bff()
        if args.suite in ("studio", "all"):
            studio_id = suite_studio()
        if args.suite in ("hash", "all"):
            suite_hash(studio_id)
        if args.suite in ("parallel", "all"):
            suite_parallel()
        if args.suite in ("exclusive", "all"):
            suite_exclusive()
        if args.suite in ("loop", "all"):
            suite_loop()
    except requests.RequestException as e:
        print(f"[FAIL] 请求失败: {e}", file=sys.stderr)
        return 1
    except RuntimeError as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        return 1
    print("[PASS] verify_workflow_studio_live")
    return 0


if __name__ == "__main__":
    sys.exit(main())
