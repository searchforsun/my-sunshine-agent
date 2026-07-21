#!/usr/bin/env python3
"""RAG 文档级多策略分块 Live 验收（spec 2026-07-21 §8）。

对五种 strategy 各 ingest 小文档并检索命中；parent_child 断言父上下文回填；
无 previewId 的 publish 应失败。

用法:
  python scripts/verify_chunk_strategies_live.py
  python scripts/verify_chunk_strategies_live.py --rag-url http://ecs4c16g:8400

环境变量: RAG_URL, RAG_ADMIN_TOKEN, RAG_TENANT_ID
"""
from __future__ import annotations

import argparse
import os
import sys
import time
import uuid

import requests

from sunshine_lib import rag_admin_headers, unwrap_r

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
DEFAULT_ADMIN_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")
DEFAULT_TENANT = os.environ.get("RAG_TENANT_ID", "verify-chunk-strategies")

STRATEGIES: list[tuple[str, dict[str, int | float], str, str]] = [
    ("markdown", {"maxSize": 1200}, "MARKDOWN_CHUNK_TOKEN", "MARKDOWN_CHUNK_TOKEN 分块"),
    ("fixed", {"maxSize": 800, "overlap": 100}, "FIXED_CHUNK_TOKEN", "FIXED_CHUNK_TOKEN 定长"),
    ("recursive", {"maxSize": 1000, "overlap": 80}, "RECURSIVE_CHUNK_TOKEN", "RECURSIVE_CHUNK_TOKEN 递归"),
    (
        "semantic",
        {"maxSize": 1200, "similarityThreshold": 0.55, "minChunkSize": 200},
        "SEMANTIC_CHUNK_TOKEN",
        "SEMANTIC_CHUNK_TOKEN 语义边界",
    ),
    (
        "parent_child",
        {"parentSize": 2000, "childSize": 400, "childOverlap": 50},
        "PARENT_CHILD_CHILD_TOKEN",
        "PARENT_CHILD_CHILD_TOKEN 父子块",
    ),
]

PARENT_CHILD_CHILD_SIZE = 400
PARENT_CHILD_PARENT_SIZE = 2000


class CheckFailure(RuntimeError):
    pass


def api_json(
    method: str,
    url: str,
    *,
    tenant_id: str,
    token: str,
    expect_code: int | None = 200,
    timeout: int = 120,
    **kwargs,
) -> dict | list | None:
    headers = kwargs.pop("headers", {})
    headers.update(rag_admin_headers(tenant_id, token))
    resp = requests.request(method, url, headers=headers, timeout=timeout, **kwargs)
    if expect_code is not None and resp.status_code != expect_code:
        raise CheckFailure(f"{method} {url} -> HTTP {resp.status_code}: {resp.text[:400]}")
    if resp.status_code == 204 or not resp.content:
        return None
    body = resp.json()
    if expect_code == 200:
        return unwrap_r(body, context=url)
    return body


def check_health(base: str) -> None:
    resp = requests.get(f"{base.rstrip('/')}/actuator/health", timeout=15)
    resp.raise_for_status()
    print("[OK] health UP")


def build_parent_child_content(token: str) -> str:
    filler = "这是用于父子块验收的填充段落，包含制度说明与背景描述。"
    sections: list[str] = []
    while sum(len(s) for s in sections) < PARENT_CHILD_PARENT_SIZE * 2:
        sections.append(filler * 8)
    mid = len(sections) // 2
    sections[mid] = f"{sections[mid]}\n\n## 报销细则\n\n{token} 位于此节，用于检索命中子块。\n"
    return "\n\n".join(sections)


def build_strategy_content(strategy: str, token: str) -> str:
    if strategy == "parent_child":
        return build_parent_child_content(token)
    return f"# 分块策略验收\n\n{token} 用于 {strategy} 策略检索验收。\n\n" + ("正文补充。" * 40)


def ensure_kb(base: str, tenant: str, token: str, kb_id: str) -> None:
    try:
        api_json(
            "POST",
            f"{base}/api/rag/admin/kbs",
            tenant_id=tenant,
            token=token,
            json={"kbId": kb_id, "displayName": "chunk-strategies verify", "description": "live verify"},
        )
    except CheckFailure as exc:
        if "409" not in str(exc) and "已存在" not in str(exc):
            raise


def ingest_strategy_doc(
    base: str,
    tenant: str,
    token: str,
    kb_id: str,
    strategy: str,
    params: dict[str, int | float],
    token_text: str,
) -> str:
    doc_id = f"chunk-{strategy}-{uuid.uuid4().hex[:8]}"
    content = build_strategy_content(strategy, token_text)
    timeout = 300 if strategy == "semantic" else 180
    api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text",
        tenant_id=tenant,
        token=token,
        timeout=timeout,
        json={
            "content": content,
            "docId": doc_id,
            "docName": doc_id,
            "displayName": doc_id,
            "strategy": strategy,
            "params": params,
        },
    )
    return doc_id


def search_hits(base: str, tenant: str, kb_id: str, query: str, *, retries: int = 5) -> list[dict]:
    url = f"{base.rstrip('/')}/api/rag/search"
    last_err: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            resp = requests.post(
                url,
                json={"query": query, "topK": 5, "kbId": kb_id},
                headers={"Content-Type": "application/json", "x-tenant-id": tenant},
                timeout=60,
            )
            resp.raise_for_status()
            data = unwrap_r(resp.json(), context="search") or {}
            hits = data.get("results") or []
            if hits:
                return hits
            last_err = CheckFailure(f"search 无命中 (attempt {attempt})")
        except requests.RequestException as exc:
            last_err = exc
        time.sleep(2 * attempt)
    raise CheckFailure(str(last_err or "search 无命中"))


def verify_strategy(
    base: str,
    tenant: str,
    token: str,
    kb_id: str,
    strategy: str,
    params: dict[str, int | float],
    token_text: str,
    query: str,
) -> None:
    doc_id = ingest_strategy_doc(base, tenant, token, kb_id, strategy, params, token_text)
    time.sleep(3 if strategy != "semantic" else 5)
    hits = search_hits(base, tenant, kb_id, query)
    top = hits[0]
    content = str(top.get("content") or "")
    if token_text not in content and strategy != "parent_child":
        raise CheckFailure(f"{strategy}: 命中内容未含 token")
    if strategy == "parent_child":
        if len(content) <= PARENT_CHILD_CHILD_SIZE:
            raise CheckFailure(
                f"parent_child: 返回长度 {len(content)} 未大于 childSize={PARENT_CHILD_CHILD_SIZE}"
            )
        if len(content) < PARENT_CHILD_PARENT_SIZE // 2:
            raise CheckFailure(
                f"parent_child: 返回长度 {len(content)} 明显小于 parent 上下文预期"
            )
    print(f"  [OK] {strategy} doc={doc_id} hits={len(hits)} content_len={len(content)}")


def verify_publish_without_preview(base: str, tenant: str, token: str, kb_id: str) -> None:
    doc_id = f"publish-gate-{uuid.uuid4().hex[:8]}"
    detail = api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/documents",
        tenant_id=tenant,
        token=token,
        json={"docId": doc_id, "displayName": doc_id, "sourceType": "markdown"},
    )
    versions = (detail or {}).get("versions") or []
    draft_version = next((v.get("version") for v in versions if v.get("status") == "draft"), None)
    if not draft_version:
        raise CheckFailure("新建文档无 draft version")
    api_json(
        "PUT",
        f"{base}/api/rag/admin/kbs/{kb_id}/documents/{doc_id}/versions/{draft_version}/content",
        tenant_id=tenant,
        token=token,
        json={"content": "# gate\n\nPUBLISH_GATE_TOKEN 草稿内容。\n"},
    )
    api_json(
        "POST",
        f"{base}/api/rag/admin/kbs/{kb_id}/documents/{doc_id}/publish",
        tenant_id=tenant,
        token=token,
        expect_code=400,
        json={},
    )
    print("  [OK] publish 无 previewId -> HTTP 400")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--tenant-id", default=DEFAULT_TENANT)
    parser.add_argument("--admin-token", default=DEFAULT_ADMIN_TOKEN)
    parser.add_argument("--kb-id", default=None, help="默认 verify-chunk-<uuid8>")
    args = parser.parse_args()

    base = args.rag_url.rstrip("/")
    kb_id = (args.kb_id or f"verify-chunk-{uuid.uuid4().hex[:8]}").strip()
    tenant = args.tenant_id.strip() or DEFAULT_TENANT
    token = args.admin_token

    print(f"[LIVE] rag={base} tenant={tenant} kb={kb_id}")
    try:
        check_health(base)
        ensure_kb(base, tenant, token, kb_id)
        print("[1/2] 五策略 ingest + search")
        for strategy, params, token_text, query in STRATEGIES:
            verify_strategy(base, tenant, token, kb_id, strategy, params, token_text, query)
        print("[2/2] publish 门禁")
        verify_publish_without_preview(base, tenant, token, kb_id)
    except (CheckFailure, requests.RequestException) as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return 1

    print("[OK] verify_chunk_strategies_live PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
