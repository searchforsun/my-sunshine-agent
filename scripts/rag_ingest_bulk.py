#!/usr/bin/env python3
"""按知识库 document 表元数据批量入库正文（Admin API → document_version + Milvus + ES）。

正文文件约定：{content_dir}/{displayName}.md（dev 语料在 docs/knowledge/）。
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

import requests

from sunshine_lib import ROOT, rag_admin_headers, unwrap_r

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
DEFAULT_ADMIN_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")
DEFAULT_CONTENT_DIR = ROOT / "docs/knowledge"


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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--kb-id", default="default", help="目标知识库 kbId")
    parser.add_argument("--tenant-id", default=os.environ.get("RAG_TENANT_ID", "default"))
    parser.add_argument("--admin-token", default=DEFAULT_ADMIN_TOKEN)
    parser.add_argument(
        "--content-dir",
        default=str(DEFAULT_CONTENT_DIR),
        help="Markdown 目录，文件名 = document.displayName + .md",
    )
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        help="chunkCount>0 的文档跳过",
    )
    args = parser.parse_args()

    content_dir = Path(args.content_dir)
    if not content_dir.is_dir():
        print(f"[FAIL] content-dir 不存在: {content_dir}", file=sys.stderr)
        return 1

    kb_id = args.kb_id.strip() or "default"
    documents = list_kb_documents(args.rag_url, args.tenant_id, args.admin_token, kb_id)
    if not documents:
        print("[FAIL] 知识库无 document 元数据，请先执行 MySQL 15-sunshine-rag-document-seed.sql", file=sys.stderr)
        return 1

    base = args.rag_url.rstrip("/")
    headers = rag_admin_headers(args.tenant_id, args.admin_token)
    ingest_url = f"{base}/api/rag/admin/kbs/{kb_id}/ingest/text"

    ingested = 0
    for doc in documents:
        doc_id = doc.get("docId") or doc.get("doc_id")
        display_name = doc.get("displayName") or doc.get("display_name")
        chunk_count = int(doc.get("chunkCount") or doc.get("chunk_count") or 0)
        if not doc_id or not display_name:
            print(f"[FAIL] document 行缺少 docId/displayName: {doc}", file=sys.stderr)
            return 1
        if args.skip_existing and chunk_count > 0:
            print(f"[SKIP] {doc_id} chunks={chunk_count}")
            continue
        md_path = content_dir / f"{display_name}.md"
        if not md_path.is_file():
            print(f"[FAIL] {doc_id}: 未找到 {md_path}", file=sys.stderr)
            return 1
        content = md_path.read_text(encoding="utf-8")
        payload = {
            "content": content,
            "docId": doc_id,
            "docName": display_name,
            "displayName": display_name,
        }
        last_err = None
        for attempt in range(1, 4):
            try:
                resp = requests.post(ingest_url, json=payload, headers=headers, timeout=300)
                resp.raise_for_status()
                data = unwrap_r(resp.json(), context=doc_id) or {}
                print(f"[OK] {doc_id} ({display_name}) chunks={data.get('chunks')}")
                ingested += 1
                last_err = None
                break
            except requests.RequestException as exc:
                last_err = exc
                if attempt < 3:
                    time.sleep(2 * attempt)
        if last_err:
            print(f"[FAIL] {doc_id}: {last_err}", file=sys.stderr)
            return 1

    print(f"[OK] ingested {ingested}/{len(documents)} documents into kb={kb_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
