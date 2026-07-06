#!/usr/bin/env python3
"""按知识库全量 re-embed：读取 DB active 版本正文 → Admin ingest/text。"""
from __future__ import annotations

import argparse
import os
import sys
import time
from urllib.parse import quote

import requests

from sunshine_lib import rag_admin_headers, unwrap_r

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://127.0.0.1:8400")
DEFAULT_ADMIN_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")


def list_kb_documents(rag_url: str, tenant_id: str, token: str, kb_id: str) -> list[dict]:
    base = rag_url.rstrip("/")
    resp = requests.get(
        f"{base}/api/rag/admin/kbs/{kb_id}/documents",
        headers=rag_admin_headers(tenant_id, token),
        timeout=120,
    )
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="list documents")
    if not isinstance(data, list):
        raise RuntimeError("document 列表响应无效")
    return [row for row in data if isinstance(row, dict)]


def fetch_active_content(
    rag_url: str, tenant_id: str, token: str, kb_id: str, doc_id: str, active_version: str
) -> str:
    base = rag_url.rstrip("/")
    url = (
        f"{base}/api/rag/admin/kbs/{quote(kb_id, safe='')}/documents/"
        f"{quote(doc_id, safe='')}/versions/{quote(active_version, safe='')}/content"
    )
    resp = requests.get(url, headers=rag_admin_headers(tenant_id, token), timeout=120)
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="get version content")
    if not isinstance(data, dict):
        raise RuntimeError(f"content 响应无效: doc={doc_id}")
    content = data.get("content") or ""
    if not content.strip():
        raise RuntimeError(f"active 版本无正文: doc={doc_id} v={active_version}")
    return content


def main() -> int:
    parser = argparse.ArgumentParser(description="按 kb 全量 re-embed（读 active 版本 → ingest/text）")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--kb-id", default="default")
    parser.add_argument("--tenant-id", default=os.environ.get("RAG_TENANT_ID", "default"))
    parser.add_argument("--admin-token", default=DEFAULT_ADMIN_TOKEN)
    parser.add_argument("--dry-run", action="store_true", help="仅统计文档数，不执行 re-embed")
    args = parser.parse_args()

    kb_id = args.kb_id.strip() or "default"
    documents = list_kb_documents(args.rag_url, args.tenant_id, args.admin_token, kb_id)
    eligible = []
    for doc in documents:
        doc_id = doc.get("docId") or doc.get("doc_id")
        display_name = doc.get("displayName") or doc.get("display_name")
        active_version = doc.get("activeVersion") or doc.get("active_version")
        if not doc_id or not display_name:
            print(f"[WARN] 跳过无效行: {doc}", file=sys.stderr)
            continue
        if not active_version:
            print(f"[SKIP] {doc_id} 无 active 版本")
            continue
        eligible.append((doc_id, display_name, active_version))

    print(f"[INFO] kb={kb_id} 可 reindex 文档数: {len(eligible)}/{len(documents)}")
    if args.dry_run:
        return 0

    base = args.rag_url.rstrip("/")
    headers = rag_admin_headers(args.tenant_id, args.admin_token)
    ingest_url = f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text"
    ok = 0
    for i, (doc_id, display_name, active_version) in enumerate(eligible, 1):
        for attempt in range(3):
            try:
                content = fetch_active_content(
                    args.rag_url, args.tenant_id, args.admin_token, kb_id, doc_id, active_version
                )
                payload = {
                    "content": content,
                    "docId": doc_id,
                    "docName": display_name,
                    "displayName": display_name,
                }
                resp = requests.post(ingest_url, headers=headers, json=payload, timeout=300)
                resp.raise_for_status()
                result = unwrap_r(resp.json(), context=f"reindex {doc_id}")
                chunks = result.get("chunks") if isinstance(result, dict) else "?"
                print(f"[{i}/{len(eligible)}] OK {doc_id} chunks={chunks}")
                ok += 1
                break
            except Exception as e:
                if attempt == 2:
                    print(f"[FAIL] {doc_id}: {e}", file=sys.stderr)
                else:
                    time.sleep(2)
    print(f"[DONE] reindex 完成: {ok}/{len(eligible)}")
    return 0 if ok == len(eligible) else 1


if __name__ == "__main__":
    raise SystemExit(main())
