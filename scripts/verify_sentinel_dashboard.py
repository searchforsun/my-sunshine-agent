#!/usr/bin/env python3
"""Sentinel Dashboard 联调验收（3.5.3）：应用注册 + gw-flow 规则 + 租户热点。

用法:
  python scripts/verify_sentinel_dashboard.py
  python scripts/verify_sentinel_dashboard.py --dashboard-url http://ecs4c16g:8858

环境变量:
  SENTINEL_DASHBOARD_URL  Dashboard 基址
  SENTINEL_CLIENT_PORT    Gateway Sentinel 命令端口（默认 8720）
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import uuid

import requests

DEFAULT_DASHBOARD = os.environ.get("SENTINEL_DASHBOARD_URL", "http://ecs4c16g:8858")
DEFAULT_CLIENT_PORT = int(os.environ.get("SENTINEL_CLIENT_PORT", "8720"))


def login(session: requests.Session, base: str, user: str, password: str) -> None:
    resp = session.post(
        f"{base}/auth/login",
        data={"username": user, "password": password},
        timeout=15,
    )
    resp.raise_for_status()
    body = resp.json()
    if not body.get("success"):
        raise RuntimeError(f"dashboard login failed: {body}")


def fetch_gateway_app(session: requests.Session, base: str) -> dict | None:
    resp = session.get(f"{base}/app/briefinfos.json?app=sunshine-gateway", timeout=15)
    resp.raise_for_status()
    data = resp.json().get("data") or []
    for item in data:
        if item.get("app") == "sunshine-gateway":
            return item
    return None


def fetch_local_gw_rules(port: int) -> list[dict]:
    resp = requests.get(f"http://127.0.0.1:{port}/gateway/getRules", timeout=5)
    resp.raise_for_status()
    return resp.json()


def register_burst(gateway_url: str) -> None:
    """制造 tenant-a 热点流量，便于 Dashboard 实时监控有数据。"""
    gw = gateway_url.rstrip("/")
    u = f"sd_{uuid.uuid4().hex[:6]}"
    requests.post(
        f"{gw}/api/auth/register",
        json={"username": u, "password": "password123", "tenantId": "tenant-a"},
        timeout=30,
    ).raise_for_status()
    tok = requests.post(
        f"{gw}/api/auth/login",
        json={"username": u, "password": "password123"},
        timeout=30,
    ).json()["data"]["token"]
    h = {"Authorization": f"Bearer {tok}"}
    for _ in range(12):
        try:
            requests.get(f"{gw}/api/conversations", headers=h, timeout=5)
        except requests.RequestException:
            pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dashboard-url", default=DEFAULT_DASHBOARD)
    parser.add_argument("--gateway-url", default="http://127.0.0.1:8000")
    parser.add_argument("--client-port", type=int, default=DEFAULT_CLIENT_PORT)
    parser.add_argument("--user", default="sentinel")
    parser.add_argument("--password", default="sentinel123")
    parser.add_argument("--skip-traffic", action="store_true")
    args = parser.parse_args()
    base = args.dashboard_url.rstrip("/")

    print("[1/4] Dashboard 登录")
    session = requests.Session()
    login(session, base, args.user, args.password)
    print("  [OK] login")

    print("[2/4] sunshine-gateway 注册（appType=网关）")
    app = fetch_gateway_app(session, base)
    if not app:
        print("[FAIL] Dashboard 未看到 sunshine-gateway", file=sys.stderr)
        return 1
    app_type = app.get("appType")
    machines = app.get("machines") or []
    healthy = [m for m in machines if m.get("healthy")]
    print(f"  [OK] appType={app_type} machines={len(machines)} healthy={len(healthy)}")
    if app_type not in (1, 11):
        print(f"[FAIL] appType={app_type}，期望网关类型 1/11", file=sys.stderr)
        return 1
    if not healthy:
        print("[WARN] 无健康机器 — 检查 gateway 是否启动且 eager=true", file=sys.stderr)

    print(f"[3/4] 本地 gw-flow 规则 (127.0.0.1:{args.client_port}/gateway/getRules)")
    try:
        rules = fetch_local_gw_rules(args.client_port)
    except requests.RequestException as exc:
        print(f"[FAIL] 无法访问 Gateway Sentinel API: {exc}", file=sys.stderr)
        return 1
    tenant_rules = [
        r for r in rules
        if r.get("resource") == "sunshine-bff-api"
        and (r.get("paramItem") or {}).get("fieldName") == "x-tenant-id"
    ]
    if not tenant_rules:
        print(f"[FAIL] 未找到 sunshine-bff-api + x-tenant-id 规则: {rules}", file=sys.stderr)
        return 1
    rule = tenant_rules[0]
    print(
        f"  [OK] resource={rule.get('resource')} count={rule.get('count')} "
        f"param={rule.get('paramItem')}"
    )

    if not args.skip_traffic:
        print("[4/4] 制造 tenant-a 流量（Dashboard → 实时监控可查看热点）")
        register_burst(args.gateway_url)
        print("  [OK] traffic sent — 打开 Dashboard「网关流控 → 实时监控」查看 tenant-a")
    else:
        print("[4/4] skip traffic")

    if healthy:
        m = healthy[0]
        ip, port = m.get("ip"), m.get("port")
        print(
            f"\n[PASS] Dashboard 联调基线通过。"
            f" UI: {base} → 网关流控 → sunshine-gateway → {ip}:{port}"
        )
    else:
        print(
            f"\n[PASS] 规则与注册通过；Dashboard 机器列表不健康时，"
            f"流控规则页可能超时（见 docs/sentinel/README.md client-ip）"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
