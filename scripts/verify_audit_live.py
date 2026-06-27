#!/usr/bin/env python3
"""3.6 审计 API Live 验收 — recent / tool-calls / sub-runs 可查。

用法:
  python3 scripts/verify_audit_live.py
  python3 scripts/verify_audit_live.py --orchestrator-url http://ecs4c16g:8200

环境变量:
  ORCHESTRATOR_URL（默认 http://127.0.0.1:8200）
"""
from __future__ import annotations

import argparse
import os
import sys

import requests

ORCH = os.environ.get("ORCHESTRATOR_URL", "http://127.0.0.1:8200").rstrip("/")


def api_json(method: str, url: str, **kwargs) -> dict:
    resp = requests.request(method, url, timeout=30, **kwargs)
    resp.raise_for_status()
    return resp.json()


def check_recent(orch_url: str) -> bool:
    body = api_json("GET", f"{orch_url}/api/audit/recent")
    if body.get("code") != 200:
        print(f"[FAIL] recent code={body.get('code')}")
        return False
    rows = body.get("data") or []
    print(f"[OK] GET /api/audit/recent -> {len(rows)} rows")
    return True


def check_query_endpoints(orch_url: str) -> bool:
    ok = True
    for path in ("/api/audit/tool-calls", "/api/audit/sub-runs?messageId=nonexistent-msg"):
        body = api_json("GET", f"{orch_url}{path}")
        if body.get("code") != 200:
            print(f"[FAIL] {path} code={body.get('code')}")
            ok = False
            continue
        print(f"[OK] GET {path} -> {len(body.get('data') or [])} rows")
    return ok


def orchestrator_ready(orch_url: str) -> bool:
    try:
        requests.get(f"{orch_url}/health", timeout=5)
        return True
    except requests.RequestException:
        return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--orchestrator-url", default=ORCH)
    args = parser.parse_args()
    orch_url = args.orchestrator_url.rstrip("/")

    if not orchestrator_ready(orch_url):
        print(f"[FAIL] Orchestrator 未就绪: {orch_url}/health", file=sys.stderr)
        print("  hint: python3 scripts/start.py 或设置 ORCHESTRATOR_URL", file=sys.stderr)
        return 1

    print(f"=== Audit Live === Orchestrator={orch_url}")
    ok = check_recent(orch_url) and check_query_endpoints(orch_url)
    if ok:
        print("[PASS] 审计三 API 可达")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
