#!/usr/bin/env python3
"""RAG Knowledge Studio 全量验收（spec §9 + T28 扩展）。

用法:
  python3 scripts/verify_rag_studio.py              # Live（需 rag-service :8400）
  python3 scripts/verify_rag_studio.py --skip-eval  # 跳过耗时 eval 全量
  python3 scripts/verify_rag_studio.py --unit-only  # 仅 Maven 单测
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

import requests

from sunshine_lib import ROOT, unwrap_r

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://127.0.0.1:8400")
DEFAULT_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")
VERIFY_TENANT = os.environ.get("RAG_VERIFY_TENANT", "verify-studio")
UNIT_TESTS = (
    "ConfigVersionServiceTest",
    "EffectiveConfigResolverTest",
    "DocumentCatalogServiceTest",
    "EvalSuiteServiceTest",
)


class CheckFailure(RuntimeError):
    pass


def admin_headers(tenant_id: str, token: str) -> dict[str, str]:
    return {
        "Content-Type": "application/json",
        "x-tenant-id": tenant_id,
        "X-Admin-Token": token,
    }


def api_json(
    method: str,
    url: str,
    *,
    tenant_id: str,
    token: str,
    expect_code: int | None = 200,
    **kwargs,
) -> dict | list | None:
    headers = kwargs.pop("headers", {})
    headers.update(admin_headers(tenant_id, token))
    resp = requests.request(method, url, headers=headers, timeout=kwargs.pop("timeout", 120), **kwargs)
    if expect_code is not None and resp.status_code != expect_code:
        raise CheckFailure(f"{method} {url} -> HTTP {resp.status_code}: {resp.text[:400]}")
    if resp.status_code == 204 or not resp.content:
        return None
    body = resp.json()
    if expect_code == 200:
        return unwrap_r(body, context=url)
    return body


def run_unit_tests() -> None:
    test_arg = ",".join(UNIT_TESTS)
    cmd = [
        "mvn", "test", "-pl", "rag-service", "-am",
        f"-Dtest={test_arg}",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-q",
    ]
    print(f"[UNIT] {' '.join(cmd)}")
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if proc.stdout:
        tail = proc.stdout[-2000:] if len(proc.stdout) > 2000 else proc.stdout
        print(tail, end="")
    if proc.returncode != 0:
        if proc.stderr:
            print(proc.stderr[-1500:], file=sys.stderr)
        raise CheckFailure(f"单测失败 exit={proc.returncode}")
    print("[OK] 单测 PASS")


def check_health(base: str) -> None:
    resp = requests.get(f"{base}/actuator/health", timeout=15)
    resp.raise_for_status()
    print("[OK] health UP")


def check_create_ingest_search(base: str, tenant: str, token: str, kb_id: str) -> None:
    api_json(
        "POST",
        f"{base}/api/rag/admin/kbs",
        tenant_id=tenant,
        token=token,
        json={"kbId": kb_id, "displayName": "verify", "description": "studio verify"},
    )
    api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text",
        tenant_id=tenant,
        token=token,
        timeout=180,
        json={
            "content": "年假可以请5天。病假需要医院证明。",
            "docName": "leave-policy-v1",
            "displayName": "请假制度",
        },
    )
    time.sleep(2)
    resp = requests.post(
        f"{base}/api/rag/search",
        headers={"Content-Type": "application/json", "x-tenant-id": tenant},
        json={"query": "年假可以请几天", "topK": 3, "kbId": kb_id},
        timeout=60,
    )
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="search")
    hits = data.get("results") or []
    if not hits:
        raise CheckFailure("入库后 search 无命中")
    print(f"[OK] create+ingest+search 命中 {len(hits)} 条")


def check_version_supersede(base: str, tenant: str, token: str, kb_id: str) -> None:
    doc_id = "supersede-doc"
    api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text",
        tenant_id=tenant,
        token=token,
        timeout=180,
        json={"content": "版本一内容 UNIQUE_V1_TOKEN", "docId": doc_id, "docName": doc_id, "displayName": "v1"},
    )
    v1 = api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text",
        tenant_id=tenant,
        token=token,
        timeout=180,
        json={"content": "版本二内容 UNIQUE_V2_TOKEN", "docId": doc_id, "docName": doc_id, "displayName": "v2"},
    )
    v2_no = (v1 or {}).get("version")
    if not v2_no:
        raise CheckFailure("ingest 未返回 version")
    api_json(
        "DELETE",
        f"{base}/api/rag/admin/kbs/{kb_id}/documents/{doc_id}/versions/1",
        tenant_id=tenant,
        token=token,
    )
    chunks = api_json(
        "GET",
        f"{base}/api/rag/admin/kbs/{kb_id}/documents/{doc_id}/chunks?version=1",
        tenant_id=tenant,
        token=token,
    )
    if isinstance(chunks, list) and chunks:
        active = [c for c in chunks if (c.get("status") or "").lower() == "active"]
        if active:
            raise CheckFailure("v1 chunk 仍为 active，supersede 失败")
    resp = requests.post(
        f"{base}/api/rag/search",
        headers={"Content-Type": "application/json", "x-tenant-id": tenant},
        json={"query": "UNIQUE_V1_TOKEN", "topK": 3, "kbId": kb_id},
        timeout=60,
    )
    resp.raise_for_status()
    v1_hits = unwrap_r(resp.json(), context="search v1").get("results") or []
    if v1_hits:
        raise CheckFailure("v1 被 supersede 后仍可检索到 UNIQUE_V1_TOKEN")
    print(f"[OK] v2 supersede v1（active version={v2_no}）")


def check_debug_stages(base: str, tenant: str, token: str, kb_id: str) -> None:
    data = api_json(
        "POST",
        f"{base}/api/rag/admin/search/debug",
        tenant_id=tenant,
        token=token,
        json={"query": "年假", "kbId": kb_id, "strategy": "hybrid+rerank", "configMode": "published"},
    )
    stages = (data or {}).get("stages") or []
    names = [s.get("name") for s in stages if isinstance(s, dict)]
    if len(stages) < 4:
        raise CheckFailure(f"debug stages 不足 4 个: {names}")
    print(f"[OK] debug 瀑布 {len(stages)} stages: {names}")


def check_publish_gate(base: str, tenant: str, token: str, kb_id: str) -> None:
    draft = api_json(
        "GET",
        f"{base}/api/rag/admin/kbs/{kb_id}/config/draft",
        tenant_id=tenant,
        token=token,
    )
    payload = dict((draft or {}).get("payload") or {})
    search = dict(payload.get("search") or {})
    search["minScore"] = 0.99
    payload["search"] = search
    api_json(
        "PUT",
        f"{base}/api/rag/admin/kbs/{kb_id}/config/draft",
        tenant_id=tenant,
        token=token,
        json=payload,
    )
    resp = requests.post(
        f"{base}/api/rag/admin/kbs/{kb_id}/config/publish",
        headers=admin_headers(tenant, token),
        timeout=300,
    )
    if resp.status_code != 422:
        raise CheckFailure(f"publish 门禁应返回 422，实际 {resp.status_code}: {resp.text[:300]}")
    body = resp.json()
    if body.get("code") != 422:
        raise CheckFailure(f"publish 门禁 code 应为 422: {body}")
    print("[OK] publish 门禁拒绝低分 draft（422）")


def check_published_isolation(base: str, tenant: str, token: str, kb_id: str) -> None:
    token_str = f"ISOLATE_{uuid.uuid4().hex[:8]}"
    api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text",
        tenant_id=tenant,
        token=token,
        timeout=180,
        json={"content": f"隔离测试 {token_str}", "docName": "iso-doc", "displayName": "iso"},
    )
    time.sleep(2)
    resp = requests.post(
        f"{base}/api/rag/search",
        headers={"Content-Type": "application/json", "x-tenant-id": tenant},
        json={"query": token_str, "topK": 3, "kbId": kb_id},
        timeout=60,
    )
    resp.raise_for_status()
    before = unwrap_r(resp.json(), context="search before").get("results") or []
    if not before:
        raise CheckFailure("published 检索基线无命中")
    draft = api_json(
        "GET",
        f"{base}/api/rag/admin/kbs/{kb_id}/config/draft",
        tenant_id=tenant,
        token=token,
    )
    payload = dict((draft or {}).get("payload") or {})
    search = dict(payload.get("search") or {})
    search["minScore"] = 0.999
    payload["search"] = search
    api_json(
        "PUT",
        f"{base}/api/rag/admin/kbs/{kb_id}/config/draft",
        tenant_id=tenant,
        token=token,
        json=payload,
    )
    resp2 = requests.post(
        f"{base}/api/rag/search",
        headers={"Content-Type": "application/json", "x-tenant-id": tenant},
        json={"query": token_str, "topK": 3, "kbId": kb_id},
        timeout=60,
    )
    resp2.raise_for_status()
    after = unwrap_r(resp2.json(), context="search after").get("results") or []
    if not after:
        raise CheckFailure("/api/rag/search 使用了 draft minScore，线上隔离失败")
    draft_debug = api_json(
        "POST",
        f"{base}/api/rag/admin/search/debug",
        tenant_id=tenant,
        token=token,
        json={"query": token_str, "kbId": kb_id, "topK": 3, "configMode": "draft"},
    )
    pub_debug = api_json(
        "POST",
        f"{base}/api/rag/admin/search/debug",
        tenant_id=tenant,
        token=token,
        json={"query": token_str, "kbId": kb_id, "topK": 3, "configMode": "published"},
    )
    draft_hits = len((draft_debug or {}).get("final") or [])
    pub_hits = len((pub_debug or {}).get("final") or [])
    if pub_hits == 0:
        raise CheckFailure("debug published 模式无命中")
    print(f"[OK] 线上 published 隔离（prod={len(after)} draft_debug={draft_hits} pub_debug={pub_hits}）")


def check_config_versions(base: str, tenant: str, token: str, kb_id: str) -> None:
    versions = api_json(
        "GET",
        f"{base}/api/rag/admin/kbs/{kb_id}/config/versions",
        tenant_id=tenant,
        token=token,
    )
    if not isinstance(versions, list) or not versions:
        raise CheckFailure("config versions 为空")
    active = [v for v in versions if v.get("active")]
    if not active:
        raise CheckFailure("无 active published 版本")
    print(f"[OK] 配置版本链 {len(versions)} 条，active=v{active[0].get('versionNo')}")


def check_draft_debug_mode(base: str, tenant: str, token: str, kb_id: str) -> None:
    pub = api_json(
        "POST",
        f"{base}/api/rag/admin/search/debug",
        tenant_id=tenant,
        token=token,
        json={"query": "年假", "kbId": kb_id, "configMode": "published", "strategy": "hybrid+rerank"},
    )
    draft = api_json(
        "POST",
        f"{base}/api/rag/admin/search/debug",
        tenant_id=tenant,
        token=token,
        json={"query": "年假", "kbId": kb_id, "configMode": "draft", "strategy": "hybrid+rerank"},
    )
    if not (pub or {}).get("stages") or not (draft or {}).get("stages"):
        raise CheckFailure("draft/published debug 均无 stages")
    print("[OK] draft / published debug 均可调用")


def check_suite_upload(base: str, tenant: str, token: str) -> None:
    suite_key = f"verify_{uuid.uuid4().hex[:8]}"
    uploaded = api_json(
        "POST",
        f"{base}/api/rag/admin/eval/suites",
        tenant_id=tenant,
        token=token,
        json={
            "suiteKey": suite_key,
            "displayName": "verify suite",
            "description": "verify-studio smoke",
            "kind": "standard",
        },
    )
    if (uploaded or {}).get("suiteKey") != suite_key:
        raise CheckFailure(f"suite 创建失败: {uploaded}")
    api_json(
        "POST",
        f"{base}/api/rag/admin/eval/suites/{suite_key}/queries",
        tenant_id=tenant,
        token=token,
        json={
            "action": "add",
            "id": "vq001",
            "query": "verify query smoke",
            "relevantDocIds": ["verify-doc"],
            "category": "verify",
        },
    )
    suites = api_json("GET", f"{base}/api/rag/admin/eval/suites", tenant_id=tenant, token=token)
    keys = {s.get("suiteKey") for s in (suites or []) if isinstance(s, dict)}
    if suite_key not in keys:
        raise CheckFailure(f"suite 列表未包含 {suite_key}")
    api_json(
        "DELETE",
        f"{base}/api/rag/admin/eval/suites/{suite_key}",
        tenant_id=tenant,
        token=token,
    )
    print(f"[OK] suite 上传/列表/删除 ({suite_key})")


def check_eval_smoke(base: str, tenant: str, token: str, kb_id: str) -> None:
    # golden-v5 suite 在 default tenant seed；eval 固定走 default + default kb
    eval_tenant = os.environ.get("RAG_EVAL_TENANT", "default")
    job = api_json(
        "POST",
        f"{base}/api/rag/admin/eval/run",
        tenant_id=eval_tenant,
        token=token,
        json={
            "suiteKey": "sunshine-regression",
            "kbId": "default",
            "configMode": "published",
            "strategy": "hybrid+rerank",
        },
    )
    job_id = (job or {}).get("jobId")
    if not job_id:
        raise CheckFailure(f"eval run 无 jobId: {job}")
    deadline = time.time() + 600
    report_id = None
    while time.time() < deadline:
        status = api_json(
            "GET",
            f"{base}/api/rag/admin/eval/jobs/{job_id}",
            tenant_id=eval_tenant,
            token=token,
        )
        state = (status or {}).get("status")
        if state == "done":
            report_id = (status or {}).get("reportId")
            break
        if state == "failed":
            raise CheckFailure(f"eval job {job_id} failed")
        time.sleep(3)
    else:
        raise CheckFailure(f"eval job {job_id} timeout")
    report = api_json(
        "GET",
        f"{base}/api/rag/admin/eval/reports/{report_id}",
        tenant_id=eval_tenant,
        token=token,
    )
    recall5 = (report or {}).get("recallAt5")
    if recall5 is None:
        summary = (report or {}).get("summary") or {}
        recall_at_k = summary.get("recall_at_k") or {}
        recall5 = recall_at_k.get("5")
    if recall5 is None:
        raise CheckFailure(f"eval report 无 Recall@5: {json.dumps(report, ensure_ascii=False)[:300]}")
    print(f"[OK] eval smoke Recall@5={recall5} (job={job_id})")


def run_live_checks(base: str, tenant: str, token: str, *, skip_eval: bool) -> None:
    check_health(base)
    kb_id = f"vst-{uuid.uuid4().hex[:8]}"
    print(f"[INFO] verify kb={kb_id} tenant={tenant}")
    check_create_ingest_search(base, tenant, token, kb_id)
    check_version_supersede(base, tenant, token, kb_id)
    check_debug_stages(base, tenant, token, kb_id)
    check_publish_gate(base, tenant, token, kb_id)
    check_published_isolation(base, tenant, token, kb_id)
    check_config_versions(base, tenant, token, kb_id)
    check_draft_debug_mode(base, tenant, token, kb_id)
    check_suite_upload(base, tenant, token)
    if not skip_eval:
        check_eval_smoke(base, tenant, token, kb_id)
    else:
        print("[SKIP] eval smoke（--skip-eval）")


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG Knowledge Studio 验收")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--tenant-id", default=VERIFY_TENANT)
    parser.add_argument("--admin-token", default=DEFAULT_TOKEN)
    parser.add_argument("--skip-eval", action="store_true", help="跳过 default kb 全量 eval smoke")
    parser.add_argument("--unit-only", action="store_true", help="仅跑 Maven 单测")
    parser.add_argument("--live-only", action="store_true", help="跳过单测")
    args = parser.parse_args()
    base = args.rag_url.rstrip("/")
    failures: list[str] = []
    if not args.live_only:
        try:
            run_unit_tests()
        except CheckFailure as exc:
            failures.append(str(exc))
    if not args.unit_only:
        try:
            run_live_checks(base, args.tenant_id, args.admin_token, skip_eval=args.skip_eval)
        except CheckFailure as exc:
            failures.append(str(exc))
        except requests.RequestException as exc:
            failures.append(f"Live 请求失败: {exc}")
    if failures:
        print("[FAIL]", "; ".join(failures), file=sys.stderr)
        return 1
    print("[OK] verify_rag_studio 全绿")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
