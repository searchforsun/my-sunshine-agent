#!/usr/bin/env python3
"""Gateway Sentinel 租户 QPS Live 验收。

前置：Nacos `sunshine.gateway.tenant-qps.count-per-tenant` 建议设为 5 便于触发。
用法:
  python scripts/verify_tenant_qps_live.py
  python scripts/verify_tenant_qps_live.py --burst 15 --min-blocked 3
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
import time
import uuid

import requests

DEFAULT_GW = "http://127.0.0.1:8000"


def register_and_login(gw: str, tenant: str) -> str:
    suffix = uuid.uuid4().hex[:8]
    username = f"qps_{tenant.replace('-', '')}_{suffix}"
    password = "password123"
    reg = requests.post(
        f"{gw}/api/auth/register",
        json={"username": username, "password": password, "tenantId": tenant},
        timeout=30,
    )
    reg.raise_for_status()
    body = reg.json()
    if body.get("code") != 200:
        raise RuntimeError(f"register failed: {body}")
    login = requests.post(
        f"{gw}/api/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    lbody = login.json()
    token = (lbody.get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {lbody}")
    return token


def hit_conversations(gw: str, token: str) -> tuple[int, dict | None]:
  headers = {"Authorization": f"Bearer {token}"}
  resp = requests.get(f"{gw}/api/conversations", headers=headers, timeout=30)
  body = None
  try:
      body = resp.json()
  except Exception:
      body = {"raw": resp.text[:200]}
  return resp.status_code, body


def burst(gw: str, token: str, n: int) -> list[tuple[int, dict | None]]:
    results: list[tuple[int, dict | None]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
        futs = [pool.submit(hit_conversations, gw, token) for _ in range(n)]
        for fut in concurrent.futures.as_completed(futs):
            results.append(fut.result())
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-url", default=DEFAULT_GW)
    parser.add_argument("--burst", type=int, default=15, help="并发请求数")
    parser.add_argument("--min-blocked", type=int, default=1, help="至少期望的 429 次数")
    args = parser.parse_args()
    gw = args.gateway_url.rstrip("/")

    print("[1/3] 登录 tenant-a / tenant-b")
    token_a = register_and_login(gw, "tenant-a")
    token_b = register_and_login(gw, "tenant-b")
    print("  [OK] tokens acquired")

    print(f"[2/3] tenant-a 并发 burst={args.burst}")
    res_a = burst(gw, token_a, args.burst)
    blocked_a = sum(1 for code, _ in res_a if code == 429)
    ok_a = sum(1 for code, _ in res_a if code == 200)
    sample_block = next((b for c, b in res_a if c == 429), None)
    print(f"  tenant-a: 200={ok_a} 429={blocked_a} sample={json.dumps(sample_block, ensure_ascii=False)}")

    print("[3/3] tenant-b 独立桶（burst=5，应不受 tenant-a 影响）")
    time.sleep(1.1)
    res_b = burst(gw, token_b, 5)
    ok_b = sum(1 for code, _ in res_b if code == 200)
    blocked_b = sum(1 for code, _ in res_b if code == 429)
    print(f"  tenant-b: 200={ok_b} 429={blocked_b}")

    if blocked_a < args.min_blocked:
        print(
            f"[FAIL] tenant-a 429={blocked_a} < min={args.min_blocked}；"
            "请确认 Nacos count-per-tenant≤5 且已 sync+重启 gateway",
            file=sys.stderr,
        )
        return 1
    if sample_block and sample_block.get("code") != 429:
        print(f"[FAIL] 429 响应体异常: {sample_block}", file=sys.stderr)
        return 1
    if ok_b < 3:
        print(f"[FAIL] tenant-b 应仍可正常访问（200={ok_b}）", file=sys.stderr)
        return 1

    print("[PASS] Sentinel 租户 QPS 限流生效，且 tenant 分桶独立")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
