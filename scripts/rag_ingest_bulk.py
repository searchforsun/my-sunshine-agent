#!/usr/bin/env python3
"""按 golden-set.corpus 批量 POST /api/rag/documents。"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

import requests
import yaml

from sunshine_lib import unwrap_r

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
GOLDEN_SET = ROOT / "docs" / "rag" / "golden-set.yaml"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--golden-set", default=str(GOLDEN_SET))
    parser.add_argument("--tenant-id", default=os.environ.get("RAG_TENANT_ID", "default"),
                        help="x-tenant-id 请求头（多租户隔离）")
    args = parser.parse_args()

    data = yaml.safe_load(Path(args.golden_set).read_text(encoding="utf-8"))
    corpus = data.get("corpus") or []
    base = args.rag_url.rstrip("/")
    headers = {"x-tenant-id": args.tenant_id.strip() or "default"}

    for item in corpus:
        path = ROOT / item["path"]
        content = path.read_text(encoding="utf-8")
        doc_name = item["display_name"]
        last_err = None
        for attempt in range(1, 4):
            try:
                resp = requests.post(
                    f"{base}/api/rag/documents",
                    json={"content": content, "docName": doc_name},
                    headers=headers,
                    timeout=300,
                )
                resp.raise_for_status()
                data = unwrap_r(resp.json(), context=doc_name) or {}
                print(f"[OK] {item['doc_id']} chunks={data.get('chunks')}")
                last_err = None
                break
            except requests.RequestException as exc:
                last_err = exc
                if attempt < 3:
                    time.sleep(2 * attempt)
        if last_err:
            print(f"[FAIL] {doc_name}: {last_err}", file=sys.stderr)
            return 1

    print(f"[OK] ingested {len(corpus)} documents")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
