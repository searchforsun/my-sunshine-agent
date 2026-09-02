#!/usr/bin/env python3
"""清库重建并按 manifest / 目录批量 ingest 扩展语料。

步骤：删除 kb 下全部 document → rag_reset（Milvus+ES）→ 按 manifest.json ingest/text。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

from sunshine_lib import ROOT, rag_admin_headers, unwrap_r

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
DEFAULT_ADMIN_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")
DEFAULT_CONTENT_DIR = ROOT / "docs/knowledge"


def list_documents(rag_url: str, tenant_id: str, token: str, kb_id: str) -> list[dict]:
    resp = requests.get(
        f"{rag_url.rstrip('/')}/api/rag/admin/kbs/{kb_id}/documents",
        headers=rag_admin_headers(tenant_id, token),
        timeout=120,
    )
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="list documents")
    if not isinstance(data, list):
        raise RuntimeError("document 列表无效")
    return [row for row in data if isinstance(row, dict)]


def delete_document(rag_url: str, tenant_id: str, token: str, kb_id: str, doc_id: str) -> None:
    resp = requests.delete(
        f"{rag_url.rstrip('/')}/api/rag/admin/kbs/{kb_id}/documents/{doc_id}",
        headers=rag_admin_headers(tenant_id, token),
        timeout=120,
    )
    resp.raise_for_status()


def rebuild_indexes(rag_url: str, token: str) -> None:
    resp = requests.post(
        f"{rag_url.rstrip('/')}/api/rag/admin/rebuild",
        headers={"X-Admin-Token": token},
        timeout=120,
    )
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="rebuild") or {}
    print(f"[OK] rebuild collection={data.get('collection')}")


def load_manifest(content_dir: Path) -> list[dict]:
    man = content_dir / "manifest.json"
    if man.is_file():
        raw = json.loads(man.read_text(encoding="utf-8"))
        if not isinstance(raw, list) or not raw:
            raise RuntimeError("manifest.json 无效")
        return raw
    # 无 manifest：按 *.md 推断（不含测试噪声）
    items = []
    for path in sorted(content_dir.glob("*.md")):
        items.append(
            {
                "docId": path.stem,
                "displayName": path.stem,
                "path": path.name,
            }
        )
    if not items:
        raise RuntimeError(f"无语料: {content_dir}")
    return items


def main() -> int:
    parser = argparse.ArgumentParser(description="删除文档 + 清向量库 + 批量 ingest")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--kb-id", default="default")
    parser.add_argument("--tenant-id", default=os.environ.get("RAG_TENANT_ID", "default"))
    parser.add_argument("--admin-token", default=DEFAULT_ADMIN_TOKEN)
    parser.add_argument("--content-dir", default=str(DEFAULT_CONTENT_DIR))
    parser.add_argument("--strategy", default="markdown")
    parser.add_argument("--skip-delete", action="store_true")
    parser.add_argument("--skip-rebuild", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    content_dir = Path(args.content_dir)
    manifest = load_manifest(content_dir)
    print(f"[INFO] corpus docs={len(manifest)} dir={content_dir}")

    if args.dry_run:
        for row in manifest:
            print(f"  - {row.get('docId')} / {row.get('displayName')}")
        return 0

    headers = rag_admin_headers(args.tenant_id, args.admin_token)
    kb_id = args.kb_id.strip() or "default"

    if not args.skip_delete:
        existing = list_documents(args.rag_url, args.tenant_id, args.admin_token, kb_id)
        print(f"[INFO] 删除现有文档 {len(existing)} 篇…")
        for row in existing:
            doc_id = row.get("docId") or row.get("doc_id")
            if not doc_id:
                continue
            delete_document(args.rag_url, args.tenant_id, args.admin_token, kb_id, doc_id)
            print(f"  [DEL] {doc_id}")

    if not args.skip_rebuild:
        rebuild_indexes(args.rag_url, args.admin_token)

    ingest_url = f"{args.rag_url.rstrip('/')}/api/rag/admin/kbs/{kb_id}/ingest/text"
    ok = 0
    for i, row in enumerate(manifest, 1):
        doc_id = row["docId"]
        display_name = row["displayName"]
        path = content_dir / row.get("path", f"{display_name}.md")
        if not path.is_file():
            print(f"[FAIL] 缺少文件: {path}", file=sys.stderr)
            return 1
        content = path.read_text(encoding="utf-8")
        payload = {
            "content": content,
            "docId": doc_id,
            "docName": display_name,
            "displayName": display_name,
            "strategy": args.strategy,
        }
        last_err = None
        for attempt in range(1, 4):
            try:
                resp = requests.post(ingest_url, headers=headers, json=payload, timeout=300)
                resp.raise_for_status()
                data = unwrap_r(resp.json(), context=doc_id) or {}
                print(
                    f"[{i}/{len(manifest)}] OK {doc_id} chars={len(content)} "
                    f"chunks={data.get('chunks')}"
                )
                ok += 1
                last_err = None
                break
            except Exception as exc:
                last_err = exc
                time.sleep(2 * attempt)
        if last_err:
            print(f"[FAIL] {doc_id}: {last_err}", file=sys.stderr)
            return 1

    print(f"[DONE] ingested {ok}/{len(manifest)}")
    return 0 if ok == len(manifest) else 1


if __name__ == "__main__":
    raise SystemExit(main())
