#!/usr/bin/env python3
"""将 docs/nacos/*.yaml 同步到线上 Nacos（唯一配置源）。

用法:
  python scripts/sync_nacos.py
  python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import requests
except ImportError:
    print("请先安装依赖: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

from sunshine_lib import ROOT

DEFAULT_DATA_IDS = [
    "sunshine-gateway.yaml",
    "sunshine-gateway-gw-flow-rules.json",
    "sunshine-auth.yaml",
    "sunshine-bff.yaml",
    "sunshine-orchestrator.yaml",
    "sunshine-workflows.yaml",
    "sunshine-llm-gateway.yaml",
    "sunshine-rag.yaml",
    "sunshine-finance.yaml",
    "sunshine-tool-manager.yaml",
    "sunshine-skill-manager.yaml",
    "sunshine-desensitize.yaml",
    "sunshine-prompt.yaml",
]

CONFIG_DIR = ROOT / "docs" / "nacos"


def upload(
    *,
    nacos_server: str,
    username: str,
    password: str,
    group: str,
    data_id: str,
) -> None:
    path = CONFIG_DIR / data_id
    if not path.is_file():
        raise FileNotFoundError(f"Config file not found: {path}")
    content = path.read_text(encoding="utf-8")
    cfg_type = "json" if data_id.endswith(".json") else "yaml"
    url = f"{nacos_server.rstrip('/')}/v1/cs/configs"
    resp = requests.post(
        url,
        data={
            "username": username,
            "password": password,
            "dataId": data_id,
            "group": group,
            "type": cfg_type,
            "content": content,
        },
        timeout=30,
    )
    resp.raise_for_status()
    if resp.text.strip().lower() not in ("true", "ok"):
        raise RuntimeError(f"Upload failed for {data_id}: {resp.text}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync docs/nacos to Nacos server")
    parser.add_argument(
        "--nacos-server",
        default="http://ecs4c16g:8848/nacos",
        help="Nacos base URL",
    )
    parser.add_argument("--username", default="nacos")
    parser.add_argument("--password", default="nacos")
    parser.add_argument("--group", default="DEFAULT_GROUP")
    parser.add_argument("--data-id", default="", help="Single dataId; default sync all")
    args = parser.parse_args()

    files = [args.data_id] if args.data_id else DEFAULT_DATA_IDS
    for data_id in files:
        upload(
            nacos_server=args.nacos_server,
            username=args.username,
            password=args.password,
            group=args.group,
            data_id=data_id,
        )
        print(f"[OK] {data_id}")

    print(f"\nSynced {len(files)} config(s) to Nacos ({args.nacos_server}).")
    print("Restart affected services to pick up changes.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
