#!/usr/bin/env python3
"""corpus-50 RAG 评测：从 eval_suite.json 同步内置评测集到 MySQL，再经 Admin API 跑门禁。

用法：
  python3 scripts/rag_eval.py --sync          # 仅同步评测集
  python3 scripts/rag_eval.py --suite-key sunshine-regression --strategy hybrid+rerank --gate
  python3 scripts/rag_eval.py --ci            # sync + regression 跑门禁 + 写报告
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

import requests

from sunshine_lib import ROOT, rag_admin_headers, run_mysql, unwrap_r

DEFAULT_RAG_URL = os.environ.get("RAG_URL", "http://ecs4c16g:8400")
DEFAULT_ADMIN_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")
DEFAULT_EVAL_JSON = ROOT / "docs/knowledge/eval_suite.json"
DEFAULT_SUITE = "sunshine-regression"


def check_gates(report: dict, gates: dict) -> list[str]:
    failures: list[str] = []
    recall_at_k = report.get("recall_at_k") or {}
    if gates.get("recallAt3Min") is not None:
        v = float(recall_at_k.get("3", 0))
        floor = float(gates["recallAt3Min"])
        if v < floor:
            failures.append(f"Recall@3 {v} < {floor}")
    if gates.get("recallAt5Min") is not None:
        v = float(recall_at_k.get("5", 0))
        floor = float(gates["recallAt5Min"])
        if v < floor:
            failures.append(f"Recall@5 {v} < {floor}")
    if gates.get("mrrMin") is not None:
        v = float(report.get("mrr", 0))
        floor = float(gates["mrrMin"])
        if v < floor:
            failures.append(f"MRR {v} < {floor}")
    if gates.get("emptyRatePositiveMax") is not None:
        v = float(report.get("empty_rate_positive", 0))
        ceiling = float(gates["emptyRatePositiveMax"])
        if v > ceiling:
            failures.append(f"正例 EmptyRate {v} > {ceiling}")
    if gates.get("emptyRateNegativeMin") is not None:
        v = float(report.get("empty_rate_negative", 0))
        floor = float(gates["emptyRateNegativeMin"])
        if v < floor:
            failures.append(f"负例 EmptyRate {v} < {floor}")
    latency = report.get("latency_ms") or {}
    if gates.get("latencyP95MsMax") is not None:
        p95 = float(latency.get("p95", 0))
        ceiling = float(gates["latencyP95MsMax"])
        if p95 > ceiling:
            failures.append(f"P95 延迟 {p95}ms > {ceiling}ms")
    return failures


def _sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def _json_sql(obj) -> str:
    return f"CAST({_sql_quote(json.dumps(obj, ensure_ascii=False))} AS JSON)"


def load_eval_suite(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or "suites" not in data:
        raise RuntimeError(f"eval_suite 无效: {path}")
    return data


def sync_suites_to_mysql(
    eval_data: dict,
    *,
    host: str,
    port: int,
    user: str,
    password: str,
) -> None:
    suites = eval_data["suites"]
    statements = ["USE sunshine_rag;"]
    for suite_key, suite in suites.items():
        items = suite.get("items") or []
        gates = suite.get("gates") or {}
        config = {"topK": [3, 5, 10], "minScore": 0.48, "gates": gates}
        display = suite.get("displayName") or suite_key
        desc = suite.get("description") or display
        statements.append(
            "INSERT INTO eval_suite "
            "(tenant_id, suite_key, display_name, description, kind, format, schema_version, "
            "storage, item_count, status, config_json) VALUES ("
            f"'default', {_sql_quote(suite_key)}, {_sql_quote(display)}, {_sql_quote(desc)}, "
            f"'standard', 'json', 1, 'mysql', {len(items)}, 'active', {_json_sql(config)}"
            ") ON DUPLICATE KEY UPDATE "
            "display_name=VALUES(display_name), description=VALUES(description), "
            "item_count=VALUES(item_count), config_json=VALUES(config_json), "
            "kind='standard', format='json', storage='mysql', content_ref=NULL;"
        )
        statements.append(
            "DELETE FROM eval_suite_item WHERE suite_id=("
            f"SELECT id FROM eval_suite WHERE tenant_id='default' AND suite_key={_sql_quote(suite_key)}"
            ");"
        )
        for sort_order, item in enumerate(items):
            item_key = item["itemKey"]
            query = item["query"]
            item_type = item.get("itemType") or "positive"
            docs = item.get("relevantDocIds") or []
            kws = item.get("relevantKeywords") or []
            category = item.get("category") or ""
            expect_empty = 1 if item.get("expectEmpty") else 0
            statements.append(
                "INSERT INTO eval_suite_item "
                "(suite_id, item_key, sort_order, query_text, item_type, relevant_doc_ids, "
                "relevant_keywords, category, expect_empty) SELECT id, "
                f"{_sql_quote(item_key)}, {sort_order}, {_sql_quote(query)}, {_sql_quote(item_type)}, "
                f"{_json_sql(docs)}, {_json_sql(kws)}, {_sql_quote(category)}, {expect_empty} "
                f"FROM eval_suite WHERE tenant_id='default' AND suite_key={_sql_quote(suite_key)};"
            )
    # 清理任何非 corpus-50 残留自定义集可不做；内置三套已覆盖
    sql = "\n".join(statements)
    run_mysql(sql, host=host, port=port, user=user, password=password)
    print(f"[OK] synced suites={list(suites.keys())}", file=sys.stderr)


def normalize_admin_report(
    report_view: dict,
    *,
    suite_key: str,
    strategy: str,
    run_at: datetime,
    tag: str,
    gates: dict,
    job_id: int,
    report_id: int,
) -> dict:
    summary = dict(report_view.get("summary") or {})
    recall_at_k = summary.get("recall_at_k")
    if not isinstance(recall_at_k, dict):
        recall_at_k = {}
    if report_view.get("recallAt5") is not None:
        recall_at_k["5"] = float(report_view["recallAt5"])
    mrr = summary.get("mrr")
    if mrr is None:
        mrr = report_view.get("mrr")
    gate_check = summary.get("gate_check")
    if not isinstance(gate_check, dict):
        gate_check = {"passed": bool(report_view.get("passedGate")), "failures": []}
    report_gates = summary.get("gates")
    if not isinstance(report_gates, dict):
        report_gates = gates
    report = {
        "run_at": run_at.isoformat(timespec="seconds"),
        "run_tag": tag,
        "date": run_at.date().isoformat(),
        "suite_key": suite_key,
        "strategy": strategy,
        "query_count": summary.get("query_count", 0),
        "recall_at_k": recall_at_k,
        "mrr": float(mrr or 0),
        "empty_rate_positive": summary.get("empty_rate_positive", 0.0),
        "empty_rate_negative": summary.get("empty_rate_negative", 0.0),
        "latency_ms": summary.get("latency_ms") or {"p50": 0.0, "p95": 0.0},
        "gates": report_gates,
        "gate_check": gate_check,
        "admin_eval": {"jobId": job_id, "reportId": report_id},
        "corpus": "corpus-50",
    }
    if not gate_check.get("failures") and report_gates:
        failures = check_gates(report, report_gates)
        if failures:
            report["gate_check"] = {"passed": False, "failures": failures}
        else:
            report["gate_check"] = {"passed": True, "failures": []}
    return report


def run_eval_via_admin_api(
    rag_url: str,
    tenant_id: str,
    token: str,
    *,
    suite_key: str,
    kb_id: str,
    strategy: str | None,
    config_mode: str,
    gates: dict,
    run_at: datetime,
    tag: str,
    poll_sec: float = 2.0,
    timeout_sec: float = 1800.0,
) -> dict:
    base = rag_url.rstrip("/")
    body: dict = {"suiteKey": suite_key, "kbId": kb_id, "configMode": config_mode}
    if strategy:
        body["strategy"] = strategy
    resp = requests.post(
        f"{base}/api/rag/admin/eval/run",
        headers=rag_admin_headers(tenant_id, token),
        json=body,
        timeout=60,
    )
    resp.raise_for_status()
    job = unwrap_r(resp.json(), context="eval run") or {}
    job_id = job.get("jobId")
    if not job_id:
        raise RuntimeError(f"eval run 无 jobId: {job}")
    deadline = time.time() + timeout_sec
    report_id = None
    while time.time() < deadline:
        st_resp = requests.get(
            f"{base}/api/rag/admin/eval/jobs/{job_id}",
            headers=rag_admin_headers(tenant_id, token),
            timeout=30,
        )
        st_resp.raise_for_status()
        status = unwrap_r(st_resp.json(), context="eval job") or {}
        state = status.get("status")
        if state == "done":
            report_id = status.get("reportId")
            break
        if state == "failed":
            raise RuntimeError(f"eval job {job_id} failed: {status}")
        time.sleep(poll_sec)
    else:
        raise TimeoutError(f"eval job {job_id} timeout after {timeout_sec}s")
    if not report_id:
        raise RuntimeError(f"eval job {job_id} done but no reportId")
    rep_resp = requests.get(
        f"{base}/api/rag/admin/eval/reports/{report_id}",
        headers=rag_admin_headers(tenant_id, token),
        timeout=60,
    )
    rep_resp.raise_for_status()
    report_view = unwrap_r(rep_resp.json(), context="eval report") or {}
    return normalize_admin_report(
        report_view,
        suite_key=suite_key,
        strategy=strategy or "vector",
        run_at=run_at,
        tag=tag,
        gates=gates,
        job_id=job_id,
        report_id=report_id,
    )


def write_markdown_report(report: dict, path: Path) -> None:
    gates = report.get("gates") or {}
    gate_result = report.get("gate_check") or {}
    recall_at_k = report.get("recall_at_k") or {}
    latency = report.get("latency_ms") or {}
    lines = [
        f"# RAG 评测报告 — {report.get('run_at')}",
        "",
        f"> corpus-50 · suite={report.get('suite_key')} · strategy={report.get('strategy')} · "
        f"{report.get('query_count')} queries · run={report.get('run_tag')}",
        "",
        "## 汇总",
        "",
        "| 指标 | 值 | 门禁 |",
        "|------|-----|------|",
        f"| Recall@3 | {recall_at_k.get('3', '—')} | ≥ {gates.get('recallAt3Min', '—')} |",
        f"| Recall@5 | {recall_at_k.get('5', '—')} | ≥ {gates.get('recallAt5Min', '—')} |",
        f"| MRR | {report.get('mrr', '—')} | ≥ {gates.get('mrrMin', '—')} |",
        f"| 正例 Empty | {report.get('empty_rate_positive', '—')} | ≤ {gates.get('emptyRatePositiveMax', '—')} |",
        f"| 负例 Empty | {report.get('empty_rate_negative', '—')} | ≥ {gates.get('emptyRateNegativeMin', '—')} |",
        f"| P95 (ms) | {latency.get('p95', '—')} | ≤ {gates.get('latencyP95MsMax', '—')} |",
        "",
        f"**门禁：{'PASS' if gate_result.get('passed') else 'FAIL'}**",
        "",
    ]
    for item in gate_result.get("failures") or []:
        lines.append(f"- {item}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="corpus-50 RAG 评测（同步评测集 + Admin 跑分）")
    parser.add_argument("--eval-json", default=str(DEFAULT_EVAL_JSON))
    parser.add_argument("--sync", action="store_true", help="将 eval_suite.json 同步到 MySQL")
    parser.add_argument("--sync-only", action="store_true", help="只同步不跑评测")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL)
    parser.add_argument("--tenant-id", default=os.environ.get("RAG_TENANT_ID", "default"))
    parser.add_argument("--suite-key", default=DEFAULT_SUITE)
    parser.add_argument("--kb-id", default="default")
    parser.add_argument(
        "--strategy",
        default="hybrid+rerank",
        choices=["vector", "hybrid", "hybrid+rerank"],
    )
    parser.add_argument("--config-mode", default="published", choices=["published", "draft"])
    parser.add_argument("--admin-token", default=DEFAULT_ADMIN_TOKEN)
    parser.add_argument("--mysql-host", default=os.environ.get("MYSQL_HOST", "ecs4c16g"))
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="root123")
    parser.add_argument("--gate", action="store_true")
    parser.add_argument("--report-md", action="store_true")
    parser.add_argument("--ci", action="store_true")
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    if args.ci:
        args.sync = True
        args.gate = True
        args.report_md = True

    eval_path = Path(args.eval_json)
    if not eval_path.is_file():
        print(f"[FAIL] 缺少 {eval_path}，先跑 generate_rag_corpus.py", file=sys.stderr)
        return 1
    eval_data = load_eval_suite(eval_path)

    if args.sync or args.sync_only:
        sync_suites_to_mysql(
            eval_data,
            host=args.mysql_host,
            port=args.mysql_port,
            user=args.mysql_user,
            password=args.mysql_password,
        )
    if args.sync_only:
        return 0

    suite = (eval_data.get("suites") or {}).get(args.suite_key)
    if not suite:
        print(f"[FAIL] eval_suite 无 suite {args.suite_key}", file=sys.stderr)
        return 1
    gates = suite.get("gates") or {}

    run_at = datetime.now()
    tag = run_at.strftime("%Y%m%d-%H%M%S")
    if args.tag:
        tag = f"{tag}-{args.tag}"
    strategy = None if args.strategy == "vector" else args.strategy
    print(f"[INFO] run suite={args.suite_key} strategy={args.strategy}", file=sys.stderr)
    report = run_eval_via_admin_api(
        args.rag_url,
        args.tenant_id,
        args.admin_token,
        suite_key=args.suite_key,
        kb_id=args.kb_id,
        strategy=strategy,
        config_mode=args.config_mode,
        gates=gates,
        run_at=run_at,
        tag=tag,
    )
    failures = list((report.get("gate_check") or {}).get("failures") or [])
    if args.gate and gates and not failures:
        failures = check_gates(report, gates)
        if failures:
            report["gate_check"] = {"passed": False, "failures": failures}
        else:
            report["gate_check"] = {"passed": True, "failures": []}

    out_dir = ROOT / "reports/rag/eval-reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"corpus50-{tag}.json"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"[OK] json: {json_path}", file=sys.stderr)
    if args.report_md:
        md_path = out_dir / f"corpus50-{tag}.md"
        write_markdown_report(report, md_path)
        print(f"[OK] md: {md_path}", file=sys.stderr)

    passed = bool((report.get("gate_check") or {}).get("passed", True))
    if args.gate and not passed:
        print("[FAIL] gates:", "; ".join(failures), file=sys.stderr)
        return 1
    print("[OK] eval finished", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
