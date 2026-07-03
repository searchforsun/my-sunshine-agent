#!/usr/bin/env python3
"""RAG 评测 CLI：经 Admin API POST /api/rag/admin/eval/run 跑 eval_suite。"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

import requests

from sunshine_lib import fetch_eval_suite_detail, rag_admin_headers, unwrap_r

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ADMIN_TOKEN = os.environ.get("RAG_ADMIN_TOKEN", "sunshine-rag-admin-dev")


def run_tag(now: datetime | None = None, extra: str | None = None) -> str:
    ts = (now or datetime.now()).strftime("%Y%m%d-%H%M%S")
    if extra:
        safe = "".join(c if c.isalnum() or c in "-_" else "-" for c in extra.strip())
        return f"{ts}-{safe}"
    return ts


def check_gates(report: dict, gates: dict) -> list[str]:
    """门禁键与 config_json.gates 一致（camelCase）。"""
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
    }
    if not gate_check.get("failures") and report_gates:
        failures = check_gates(report, report_gates)
        if failures:
            report["gate_check"] = {"passed": False, "failures": failures}
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
    run_at: datetime | None = None,
    tag: str = "",
    poll_sec: float = 2.0,
    timeout_sec: float = 900.0,
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
            raise RuntimeError(f"eval job {job_id} failed")
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
    now = run_at or datetime.now()
    return normalize_admin_report(
        report_view,
        suite_key=suite_key,
        strategy=strategy or "vector",
        run_at=now,
        tag=tag,
        gates=gates,
        job_id=job_id,
        report_id=report_id,
    )


def write_markdown_report(report: dict, path: Path) -> None:
    gates = report.get("gates") or {}
    gate_result = report.get("gate_check") or {}
    lines = [
        f"# RAG 评测报告 — {report.get('run_at', report.get('date'))}",
        "",
        f"> suite_key={report.get('suite_key')} · strategy={report.get('strategy')} · "
        f"{report.get('query_count')} queries · run={report.get('run_tag')}",
        "",
        "## 汇总指标",
        "",
        "| 指标 | 值 | 门禁 |",
        "|------|-----|------|",
    ]
    recall_at_k = report.get("recall_at_k") or {}
    lines.append(f"| Recall@3 | {recall_at_k.get('3', '—')} | ≥ {gates.get('recallAt3Min', '—')} |")
    lines.append(f"| Recall@5 | {recall_at_k.get('5', '—')} | ≥ {gates.get('recallAt5Min', '—')} |")
    lines.append(f"| MRR | {report.get('mrr', '—')} | ≥ {gates.get('mrrMin', '—')} |")
    lines.append(f"| 正例 EmptyRate | {report.get('empty_rate_positive', '—')} | ≤ {gates.get('emptyRatePositiveMax', '—')} |")
    lines.append(f"| 负例 EmptyRate | {report.get('empty_rate_negative', '—')} | ≥ {gates.get('emptyRateNegativeMin', '—')} |")
    latency = report.get("latency_ms") or {}
    lines.append(f"| P95 延迟 (ms) | {latency.get('p95', '—')} | ≤ {gates.get('latencyP95MsMax', '—')} |")
    lines.append("")
    if gate_result.get("passed"):
        lines.append("**门禁：PASS**")
    else:
        lines.append("**门禁：FAIL**")
        for item in gate_result.get("failures") or []:
            lines.append(f"- {item}")
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_regression_report(report: dict, path: Path) -> None:
    gate = report.get("gate_check") or {}
    status = "PASS" if gate.get("passed") else "FAIL"
    recall_at_k = report.get("recall_at_k") or {}
    latency = report.get("latency_ms") or {}
    block = [
        f"## {report.get('run_at')} — {report.get('suite_key')} / {report.get('strategy')}",
        "",
        f"- run_tag: `{report.get('run_tag')}`",
        f"- queries: {report.get('query_count')}",
        f"- Recall@5: {recall_at_k.get('5')} · MRR: {report.get('mrr')}",
        f"- 正例 Empty: {report.get('empty_rate_positive')} · 负例 Empty: {report.get('empty_rate_negative')}",
        f"- P95: {latency.get('p95')} ms",
        f"- **门禁: {status}**",
    ]
    for item in gate.get("failures") or []:
        block.append(f"- FAIL: {item}")
    block.append("")
    if path.exists():
        existing = path.read_text(encoding="utf-8")
        path.write_text(existing.rstrip() + "\n\n" + "\n".join(block) + "\n", encoding="utf-8")
    else:
        header = [
            f"# RAG 回归门禁 — {report.get('date')}",
            "",
            "> 由 `rag_eval.py --regression-md` 或 CI `rag-eval` workflow 生成。",
            "",
        ]
        path.write_text("\n".join(header + block), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="经 Admin API 跑 eval_suite 标准检索评测")
    parser.add_argument("--rag-url", default=os.environ.get("RAG_URL", "http://localhost:8400"))
    parser.add_argument("--tenant-id", default=os.environ.get("RAG_TENANT_ID", "default"))
    parser.add_argument("--suite-key", default="sunshine-regression", help="eval_suite.suite_key")
    parser.add_argument("--kb-id", default="default")
    parser.add_argument(
        "--strategy",
        default="vector",
        choices=["vector", "hybrid", "hybrid+rerank"],
    )
    parser.add_argument("--config-mode", default="published", choices=["published", "draft"])
    parser.add_argument("--admin-token", default=DEFAULT_ADMIN_TOKEN)
    parser.add_argument("--gate", action="store_true", help="未达 gates 时 exit 1")
    parser.add_argument("--report-md", action="store_true")
    parser.add_argument("--regression-md", action="store_true")
    parser.add_argument("--ci", action="store_true", help="--gate --report-md --regression-md")
    parser.add_argument("--tag", default="", help="报告文件名附加标记")
    args = parser.parse_args()
    if args.ci:
        args.gate = True
        args.report_md = True
        args.regression_md = True

    run_at = datetime.now()
    tag = run_tag(run_at, args.tag or None)
    strategy = None if args.strategy == "vector" else args.strategy

    detail = fetch_eval_suite_detail(args.rag_url, args.tenant_id, args.admin_token, args.suite_key)
    config = detail.get("config") or {}
    gates = config.get("gates") or {}

    print(f"[INFO] eval via admin API suite_key={args.suite_key}", file=sys.stderr)
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
    gate_check = report.get("gate_check") or {}
    failures = list(gate_check.get("failures") or [])
    if args.gate and gates and not failures:
        failures = check_gates(report, gates)
        if failures:
            report["gate_check"] = {"passed": False, "failures": failures}

    out_dir = ROOT / "reports/rag/eval-reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"baseline-{tag}.json"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"[OK] json report: {json_path}", file=sys.stderr)

    if args.report_md:
        md_path = out_dir / f"rag-eval-report-{tag}.md"
        write_markdown_report(report, md_path)
        print(f"[OK] markdown report: {md_path}", file=sys.stderr)
    if args.regression_md:
        reg_path = ROOT / "reports/rag" / f"regression-{run_at.date().isoformat()}.md"
        write_regression_report(report, reg_path)
        print(f"[OK] regression report: {reg_path}", file=sys.stderr)

    passed = bool((report.get("gate_check") or {}).get("passed", True))
    if args.gate and gates and not passed:
        print("[FAIL] gates:", "; ".join(failures), file=sys.stderr)
        return 1
    print("[OK] eval finished", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
