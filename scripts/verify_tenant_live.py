#!/usr/bin/env python3
"""多租户 PR-1 Live 验收：注册 → tenant-a 入库 → RAG 隔离 → knowledge-qa 全链路。"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
TIMEOUT_SEC = int(os.environ.get("PHASE2_AGENT_TIMEOUT_SEC", "120"))
RAG_STEP_ID = "node-rag"


def wait_port(port: int, timeout: float = 120.0) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2)
            s.close()
            return True
        except OSError:
            time.sleep(2)
    return False


def start_core_services() -> None:
    from sunshine_lib import start_java_detached

    services = [
        ("auth", "auth-center", "sunshine-auth", 8100),
        ("rag", "rag-service", "sunshine-rag", 8400),
        ("orchestrator", "orchestrator", "sunshine-orchestrator", 8200),
        ("bff", "bff", "sunshine-bff", 8001),
        ("gateway", "gateway", "sunshine-gateway", 8000),
    ]
    for name, module, artifact, port in services:
        if wait_port(port, timeout=2):
            print(f"[SKIP] {name} :{port} already up")
            continue
        print(f"[START] {name} :{port}")
        start_java_detached(module, artifact, service_name=name, wait_sec=5)
    for name, _, _, port in services:
        ok = wait_port(port)
        if not ok:
            raise RuntimeError(f"service not ready: {name} :{port}")


def api_json(method: str, url: str, **kwargs) -> dict:
    resp = requests.request(method, url, timeout=120, **kwargs)
    resp.raise_for_status()
    return resp.json()


def create_conversation(gw: str, token: str) -> str:
    body = api_json("POST", f"{gw}/api/conversations", headers={"Authorization": f"Bearer {token}"})
    conv_id = (body.get("data") or body).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {body}")
    return conv_id


def chat_sse(gw: str, token: str, conv_id: str, query: str) -> str:
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("curl not found (required for SSE)")
    payload = json.dumps({"content": query, "conversationId": conv_id}, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as f:
        f.write(payload)
        tmp = f.name
    try:
        proc = subprocess.run(
            [
                curl, "-N", "-s", "-m", str(TIMEOUT_SEC),
                "-X", "POST", f"{gw}/api/chat/stream",
                "-H", f"Authorization: Bearer {token}",
                "-H", "Content-Type: application/json",
                "--data-binary", f"@{tmp}",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        raw = proc.stdout or proc.stderr
        if proc.returncode != 0 and not raw.strip():
            raise RuntimeError(f"SSE failed (curl exit {proc.returncode})")
        return raw
    finally:
        os.unlink(tmp)


def parse_done_steps(raw: str) -> list[dict]:
    steps: list[dict] = []
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload:
            continue
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if obj.get("type") == "step" and obj.get("lifecycle") == "done":
            steps.append(obj)
    return steps


def rag_hit_count(step: dict | None) -> int | None:
    if not step:
        return None
    meta = step.get("metadata") or {}
    hit = meta.get("hitCount")
    if hit is not None:
        try:
            return int(hit)
        except (TypeError, ValueError):
            pass
    detail = str(step.get("detail") or "")
    if "命中 0 条" in detail or "找到 0 条" in detail:
        return 0
    for prefix in ("命中 ", "找到 "):
        if prefix in detail:
            frag = detail.split(prefix, 1)[1]
            num = ""
            for ch in frag:
                if ch.isdigit():
                    num += ch
                else:
                    break
            if num:
                return int(num)
    return None


def verify_chat_tenant(gw: str, token: str, tenant: str, query: str, expect_hits: int) -> tuple[bool, str]:
    conv_id = create_conversation(gw, token)
    chat_query = f"#knowledge-qa {query}"
    raw = chat_sse(gw, token, conv_id, chat_query)
    steps = parse_done_steps(raw)
    rag_step = next((s for s in steps if str(s.get("id")) == RAG_STEP_ID), None)
    hits = rag_hit_count(rag_step)
    step_ids = [str(s.get("id")) for s in steps]
    detail = (
        f"tenant={tenant} steps={step_ids} "
        f"{RAG_STEP_ID}.hitCount={hits} expect={expect_hits}"
    )
    if rag_step is None:
        return False, detail + " (missing rag step)"
    if hits is None:
        return False, detail + f" metadata={rag_step.get('metadata')}"
    if expect_hits > 0:
        return hits > 0, detail
    return hits == 0, detail


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-url", default="http://127.0.0.1:8000")
    parser.add_argument("--rag-url", default="http://127.0.0.1:8400")
    parser.add_argument("--start-services", action="store_true", help="启动 auth/rag/orchestrator/bff/gateway")
    parser.add_argument("--skip-ingest", action="store_true", help="跳过入库（假定 tenant-a 已有语料）")
    parser.add_argument("--skip-chat", action="store_true", help="跳过 knowledge-qa 全链路验收")
    parser.add_argument("--query", default="差旅报销管理办法")
    args = parser.parse_args()

    if args.start_services:
        start_core_services()

    suffix = uuid.uuid4().hex[:8]
    user_a = f"tenanta_{suffix}"
    user_b = f"tenantb_{suffix}"
    password = "password123"
    gw = args.gateway_url.rstrip("/")
    rag = args.rag_url.rstrip("/")

    print("[1/6] 注册 tenant-a / tenant-b 用户")
    for username, tenant in ((user_a, "tenant-a"), (user_b, "tenant-b")):
        body = api_json(
            "POST",
            f"{gw}/api/auth/register",
            json={"username": username, "password": password, "tenantId": tenant},
        )
        if body.get("code") != 200:
            print(f"[FAIL] register {username}: {body}", file=sys.stderr)
            return 1
        print(f"  [OK] {username} -> {tenant}")

    print("[2/6] 登录并校验 JWT tenantId")
    tokens: dict[str, str] = {}
    for username, tenant in ((user_a, "tenant-a"), (user_b, "tenant-b")):
        login = api_json(
            "POST",
            f"{gw}/api/auth/login",
            json={"username": username, "password": password},
        )
        if login.get("code") != 200:
            print(f"[FAIL] login {username}: {login}", file=sys.stderr)
            return 1
        token = login["data"]["token"]
        tokens[tenant] = token
        me = api_json("GET", f"{gw}/api/auth/me", headers={"Authorization": f"Bearer {token}"})
        print(f"  [OK] {username} me -> {json.dumps(me.get('data'), ensure_ascii=False)}")

    if not args.skip_ingest:
        print("[3/6] tenant-a 批量入库（golden-set）")
        import subprocess

        proc = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "rag_ingest_bulk.py"),
                "--rag-url",
                rag,
                "--tenant-id",
                "tenant-a",
            ],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
        )
        print(proc.stdout, end="")
        if proc.returncode != 0:
            print(proc.stderr, file=sys.stderr)
            return 1
    else:
        print("[3/6] skip ingest")

    print(f"[4/6] RAG 检索隔离 query={args.query!r}")
    results: dict[str, int] = {}
    for tenant in ("tenant-a", "tenant-b"):
        resp = api_json(
            "POST",
            f"{rag}/api/rag/search",
            headers={"x-tenant-id": tenant},
            json={"query": args.query, "topK": 5, "strategy": "hybrid+rerank"},
        )
        hits = resp.get("results") or []
        results[tenant] = len(hits)
        top = hits[0].get("docName") if hits else None
        print(f"  tenant={tenant} hits={len(hits)} top={top!r}")

    print("[5/6] 判定 RAG API")
    ok_a = results["tenant-a"] > 0
    ok_b = results["tenant-b"] == 0
    if not (ok_a and ok_b):
        print(f"[FAIL] tenant-a hits={results['tenant-a']}, tenant-b hits={results['tenant-b']}", file=sys.stderr)
        if not ok_a:
            print("  hint: Milvus 可能未重建或 tenant-a 未入库，可去掉 --skip-ingest 重跑", file=sys.stderr)
        if not ok_b:
            print("  hint: tenant-b 不应命中 tenant-a 语料，检查 Milvus/ES tenant_id 过滤", file=sys.stderr)
        return 1
    print("[PASS] tenant-a 有命中，tenant-b 0 命中 — RAG API 隔离生效")

    if args.skip_chat:
        print("[6/6] skip chat")
        return 0

    print(f"[6/6] knowledge-qa 全链路 (#knowledge-qa {args.query!r})")
    chat_ok = True
    for tenant, expect in (("tenant-a", 1), ("tenant-b", 0)):
        ok, detail = verify_chat_tenant(gw, tokens[tenant], tenant, args.query, expect)
        status = "OK" if ok else "FAIL"
        print(f"  [{status}] {detail}")
        chat_ok = chat_ok and ok

    if chat_ok:
        print("[PASS] orchestrator → rag-service 多租户透传生效（knowledge-qa）")
        return 0

    print("[FAIL] knowledge-qa 全链路未通过", file=sys.stderr)
    print("  hint: 确认 llm-gateway :8300 已启动；查 orchestrator 日志 [KnowledgeRetrieval]", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
