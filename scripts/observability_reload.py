#!/usr/bin/env python3
"""重载 ecs4c16g Prometheus 配置（docker compose 更新后执行）。

用法:
  python scripts/observability_reload.py
"""
from __future__ import annotations

import argparse
import sys

import requests

DEFAULT_PROM = "http://ecs4c16g:9090"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prometheus-url", default=DEFAULT_PROM)
    args = parser.parse_args()
    url = f"{args.prometheus_url.rstrip('/')}/-/reload"
    resp = requests.post(url, timeout=15)
    if resp.status_code not in (200, 202):
        print(f"[FAIL] reload {resp.status_code}: {resp.text}", file=sys.stderr)
        return 1
    print("[OK] Prometheus config reloaded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
