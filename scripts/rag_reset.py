#!/usr/bin/env python3
"""清库重建 — 调用 rag-service POST /api/rag/admin/rebuild。"""
from __future__ import annotations

import argparse
import os
import sys

import requests

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
DEFAULT_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG Milvus 清库重建")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--token", default=DEFAULT_TOKEN)
    args = parser.parse_args()

    url = f"{args.rag_url.rstrip('/')}/api/rag/admin/rebuild"
    resp = requests.post(url, headers={"X-Admin-Token": args.token}, timeout=120)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 200:
        print(f"[FAIL] rebuild: {body}", file=sys.stderr)
        return 1
    print(f"[OK] rebuild collection={body.get('collection')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
