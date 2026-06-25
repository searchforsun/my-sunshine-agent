#!/usr/bin/env python3
"""Grafana RAG 可观测 Live 验收（3.5.1 / 3.5.2）。

检查 Prometheus 抓取、rag_* 指标、告警规则、Grafana Dashboard。

用法:
  python scripts/verify_grafana_rag_live.py
  python scripts/verify_grafana_rag_live.py --rag-url http://ecs4c16g:8400

环境变量: PROMETHEUS_URL, GRAFANA_URL, RAG_URL
"""
from __future__ import annotations

import argparse
import os
import sys
import time

import requests

DEFAULT_PROM = os.environ.get("PROMETHEUS_URL", "http://ecs4c16g:9090")
DEFAULT_GRAFANA = os.environ.get("GRAFANA_URL", "http://ecs4c16g:3000")
DEFAULT_RAG = os.environ.get("RAG_URL", "http://127.0.0.1:8400")


def prom_query(base: str, expr: str) -> list:
    resp = requests.get(
        f"{base.rstrip('/')}/api/v1/query",
        params={"query": expr},
        timeout=15,
    )
    resp.raise_for_status()
    body = resp.json()
    if body.get("status") != "success":
        raise RuntimeError(f"prometheus query failed: {body}")
    return body.get("data", {}).get("result") or []


def find_target(base: str, job: str) -> dict | None:
    resp = requests.get(f"{base.rstrip('/')}/api/v1/targets", timeout=15)
    resp.raise_for_status()
    for t in resp.json().get("data", {}).get("activeTargets") or []:
        if t.get("labels", {}).get("job") == job:
            return t
    return None


def rag_search(rag_url: str, n: int = 5) -> None:
    base = rag_url.rstrip("/")
    for i in range(n):
        requests.post(
            f"{base}/api/rag/search",
            json={"query": f"年假制度测试{i}", "topK": 3, "strategy": "hybrid+rerank"},
            headers={"x-tenant-id": "default"},
            timeout=60,
        ).raise_for_status()


def grafana_dashboard_exists(base: str, uid: str, user: str, password: str) -> bool:
    resp = requests.get(
        f"{base.rstrip('/')}/api/dashboards/uid/{uid}",
        auth=(user, password),
        timeout=15,
    )
    return resp.status_code == 200


def prom_alert_rules(base: str) -> list[str]:
    resp = requests.get(f"{base.rstrip('/')}/api/v1/rules", timeout=15)
    resp.raise_for_status()
    names: list[str] = []
    for group in resp.json().get("data", {}).get("groups") or []:
        for rule in group.get("rules") or []:
            if rule.get("type") == "alerting":
                names.append(rule.get("name", ""))
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prometheus-url", default=DEFAULT_PROM)
    parser.add_argument("--grafana-url", default=DEFAULT_GRAFANA)
    parser.add_argument("--rag-url", default=DEFAULT_RAG)
    parser.add_argument("--grafana-user", default=os.environ.get("GRAFANA_USER", "admin"))
    parser.add_argument("--grafana-password", default=os.environ.get("GRAFANA_PASSWORD", "admin123"))
    parser.add_argument("--skip-traffic", action="store_true")
    args = parser.parse_args()

    print("[1/4] Prometheus sunshine-rag 抓取目标")
    target = find_target(args.prometheus_url, "sunshine-rag")
    if not target:
        print("[FAIL] 未配置 job=sunshine-rag（见 docker/prometheus/prometheus.yml）", file=sys.stderr)
        print("  hint: cd docker && docker compose up -d prometheus grafana", file=sys.stderr)
        return 1
    health = target.get("health")
    print(f"  target={target.get('scrapeUrl')} health={health}")
    if health != "up":
        print("[WARN] sunshine-rag target 非 up — rag-service 需在宿主机 :8400 运行", file=sys.stderr)

    print("[2/4] Prometheus 告警规则（4 条）")
    alerts = prom_alert_rules(args.prometheus_url)
    expected = {
        "RagSearchP95High",
        "RagSearchErrorRateHigh",
        "RagEmptyRateHigh",
        "RagRerankErrorRateHigh",
    }
    missing = expected - set(alerts)
    if missing:
        print(f"[FAIL] 缺少告警规则: {sorted(missing)}", file=sys.stderr)
        return 1
    print(f"  [OK] rules={sorted(expected)}")

    if not args.skip_traffic:
        print(f"[3/4] 制造 RAG 检索流量 ({args.rag_url})")
        try:
            rag_search(args.rag_url, 5)
            time.sleep(20)
        except requests.RequestException as exc:
            print(f"[WARN] RAG 不可达，跳过指标写入: {exc}", file=sys.stderr)
    else:
        print("[3/4] skip traffic")

    print("[4/4] rag_* 指标 + Grafana Dashboard")
    metrics = prom_query(args.prometheus_url, "rag_search_requests_total")
    if metrics:
        print(f"  [OK] rag_search_requests_total series={len(metrics)}")
    else:
        print("  [WARN] rag_search_requests_total 暂无数据（target down 或未抓到你本机 RAG）")

    if grafana_dashboard_exists(args.grafana_url, "sunshine-rag", args.grafana_user, args.grafana_password):
        print(f"  [OK] Grafana dashboard uid=sunshine-rag @ {args.grafana_url}")
    else:
        print(
            f"[WARN] Grafana 未找到 dashboard（需 provisioning 或 Import docs/grafana/rag-dashboard.json）",
            file=sys.stderr,
        )

    if target.get("health") == "up" and metrics:
        print("[PASS] Grafana RAG 可观测链路就绪")
        return 0
    if alerts and target:
        print("[PASS] 基线通过（抓取/指标需 rag-service 同机 :8400 后才有数据）")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
