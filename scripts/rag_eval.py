#!/usr/bin/env python3
 
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime
from pathlib import Path

import requests
import yaml

ROOT = Path(__file__).resolve().parent.parent


def run_tag(now: datetime | None = None, extra: str | None = None) -> str:
    """生成不覆盖历史报告的文件名片段：YYYYMMDD-HHMMSS。"""
    ts = (now or datetime.now()).strftime("%Y%m%d-%H%M%S")
    if extra:
        safe = "".join(c if c.isalnum() or c in "-_" else "-" for c in extra.strip())
        return f"{ts}-{safe}"
    return ts


def load_golden(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def doc_id_to_name(corpus: list) -> dict[str, str]:
    return {c["doc_id"]: c["display_name"] for c in corpus}


def search(rag_url: str, query: str, top_k: int, strategy: str | None = None) -> tuple[list[dict], float]:
    t0 = time.perf_counter()
    body: dict = {"query": query, "topK": top_k}
    if strategy:
        body["strategy"] = strategy
    resp = requests.post(
        f"{rag_url.rstrip('/')}/api/rag/search",
        json=body,
        timeout=60,
    )
    resp.raise_for_status()
    ms = (time.perf_counter() - t0) * 1000
    results = resp.json().get("results") or []
    return results, ms


def recall_at_k(hits: list[dict], relevant_names: set[str], k: int, min_score: float) -> float:
    if not relevant_names:
        return 0.0
    for h in hits[:k]:
        if h.get("score", 0) < min_score:
            continue
        if h.get("docName") in relevant_names:
            return 1.0
    return 0.0


def mrr(hits: list[dict], relevant_names: set[str], min_score: float) -> float:
    if not relevant_names:
        return 0.0
    for i, h in enumerate(hits, start=1):
        if h.get("score", 0) < min_score:
            continue
        if h.get("docName") in relevant_names:
            return 1.0 / i
    return 0.0


def first_relevant_rank(hits: list[dict], relevant_names: set[str], min_score: float) -> int | None:
    for i, h in enumerate(hits, start=1):
        if h.get("score", 0) < min_score:
            continue
        if h.get("docName") in relevant_names:
            return i
    return None


def check_gates(report: dict, gates: dict) -> list[str]:
    failures: list[str] = []
    if gates.get("recall_at_3_min") is not None:
        v = report["recall_at_k"]["3"]
        if v < float(gates["recall_at_3_min"]):
            failures.append(f"recall@3 {v} < {gates['recall_at_3_min']}")
    if gates.get("recall_at_5_min") is not None:
        v = report["recall_at_k"]["5"]
        if v < float(gates["recall_at_5_min"]):
            failures.append(f"recall@5 {v} < {gates['recall_at_5_min']}")
    if gates.get("mrr_min") is not None:
        if report["mrr"] < float(gates["mrr_min"]):
            failures.append(f"mrr {report['mrr']} < {gates['mrr_min']}")
    if gates.get("empty_rate_positive_max") is not None:
        if report["empty_rate_positive"] > float(gates["empty_rate_positive_max"]):
            failures.append(
                f"positive empty_rate {report['empty_rate_positive']} > {gates['empty_rate_positive_max']}"
            )
    if gates.get("empty_rate_negative_min") is not None:
        if report["empty_rate_negative"] < float(gates["empty_rate_negative_min"]):
            failures.append(
                f"negative empty_rate {report['empty_rate_negative']} < {gates['empty_rate_negative_min']}"
            )
    if gates.get("latency_p95_ms_max") is not None:
        if report["latency_ms"]["p95"] > float(gates["latency_p95_ms_max"]):
            failures.append(
                f"p95 latency {report['latency_ms']['p95']}ms > {gates['latency_p95_ms_max']}ms"
            )
    return failures


def filter_queries(queries: list[dict], suite: str, eval_cfg: dict) -> list[dict]:
    """按评测套件筛选 query：v5=回归轨（排除 adversarial），v6=难例轨，all=全量。"""
    if suite == "core":
        return [
            q for q in queries
            if q["id"].startswith("q0") and q["id"][1:4].isdigit() and int(q["id"][1:4]) <= 12
        ]
    suites_cfg = eval_cfg.get("suites") or {}
    if suite == "v5":
        exclude = set((suites_cfg.get("v5") or {}).get("exclude_categories") or ["adversarial"])
        return [q for q in queries if q.get("category") not in exclude]
    if suite == "v6":
        include = set((suites_cfg.get("v6") or {}).get("include_categories") or ["adversarial"])
        return [q for q in queries if q.get("category") in include]
    return queries


def write_markdown_report(report: dict, path: Path) -> None:
    gates = report.get("production_gates") or {}
    gate_result = report.get("gate_check") or {}
    lines = [
        f"# RAG 评测报告 — {report.get('run_at', report.get('date'))}",
        "",
        f"> golden-set v{report.get('golden_version')} · suite={report.get('suite')} · "
        f"strategy={report.get('strategy')} · {report.get('query_count')} queries · "
        f"min_score={report.get('min_score')} · run={report.get('run_tag')}",
        "",
        "## 汇总指标",
        "",
        "| 指标 | 值 | 生产门禁 | 结果 |",
        "|------|-----|----------|------|",
    ]
    g3 = gates.get("recall_at_3_min", "—")
    g5 = gates.get("recall_at_5_min", "—")
    lines.append(
        f"| Recall@3 | {report['recall_at_k']['3']} | ≥ {g3} | "
        f"{'PASS' if report['recall_at_k']['3'] >= float(g3 or 0) else 'FAIL'} |"
    )
    lines.append(
        f"| Recall@5 | {report['recall_at_k']['5']} | ≥ {g5} | "
        f"{'PASS' if report['recall_at_k']['5'] >= float(g5 or 0) else 'FAIL'} |"
    )
    lines.append(
        f"| MRR | {report['mrr']} | ≥ {gates.get('mrr_min', '—')} | "
        f"{'PASS' if not gate_result.get('failures') or 'mrr' not in str(gate_result.get('failures')) else '—'} |"
    )
    lines.append(f"| 正例 EmptyRate | {report['empty_rate_positive']} | = 0 | — |")
    lines.append(f"| 负例 EmptyRate | {report['empty_rate_negative']} | ≥ {gates.get('empty_rate_negative_min', '—')} | — |")
    lines.append(f"| P50 延迟 (ms) | {report['latency_ms']['p50']} | — | — |")
    lines.append(f"| P95 延迟 (ms) | {report['latency_ms']['p95']} | ≤ {gates.get('latency_p95_ms_max', '—')} | — |")
    lines.append("")
    if gate_result.get("passed"):
        lines.append("**生产门禁：PASS**")
    else:
        lines.append("**生产门禁：FAIL**")
        for f in gate_result.get("failures") or []:
            lines.append(f"- {f}")
    lines.append("")
    lines.append("## 分类型 Recall@3")
    lines.append("")
    lines.append("| category | Recall@3 |")
    lines.append("|----------|----------|")
    for cat, val in sorted((report.get("by_category_recall_at_3") or {}).items()):
        lines.append(f"| {cat} | {val} |")
    badcases = report.get("badcases") or {}
    lines.append("")
    lines.append("## Badcase（Recall@3 未命中）")
    lines.append("")
    pos_fails = badcases.get("positive_miss") or []
    if not pos_fails:
        lines.append("无。")
    else:
        lines.append("| id | query | 期望 doc | Top3 命中 |")
        lines.append("|----|-------|----------|-----------|")
        for b in pos_fails:
            top3 = "; ".join(f"{x['docName']}({x['score']})" for x in b.get("top3", [])[:3])
            lines.append(f"| {b['id']} | {b['query']} | {', '.join(b.get('expected', []))} | {top3} |")
    neg_fp = badcases.get("negative_false_positive") or []
    lines.append("")
    lines.append("## 负例误召回")
    lines.append("")
    if not neg_fp:
        lines.append("无。")
    else:
        for b in neg_fp:
            lines.append(f"- **{b['id']}** {b['query']} → {b.get('top3', [])}")
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-url", default=os.environ.get("RAG_URL", "http://localhost:8400"))
    parser.add_argument("--golden-set", default=str(ROOT / "docs/rag/golden-set.yaml"))
    parser.add_argument("--suite", default="all", choices=["all", "core", "v5", "v6"])
    parser.add_argument(
        "--strategy",
        default="vector",
        choices=["vector", "hybrid", "hybrid+rerank"],
        help="检索策略（透传 RAG API strategy）",
    )
    parser.add_argument("--gate", action="store_true", help="未达 production_gates 时 exit 1")
    parser.add_argument("--report-md", action="store_true", help="输出 Markdown 报告")
    parser.add_argument("--tag", default="", help="报告文件名附加标记（可选，如 v5-smoke）")
    args = parser.parse_args()

    run_at = datetime.now()
    tag = run_tag(run_at, args.tag or None)

    data = load_golden(Path(args.golden_set))
    id2name = doc_id_to_name(data["corpus"])
    eval_cfg = data.get("eval") or {}
    top_ks = eval_cfg.get("top_k") or [3, 5, 10]
    min_score = float(eval_cfg.get("min_score", 0.48))
    gates = eval_cfg.get("production_gates") or {}
    max_k = max(top_ks)

    queries = filter_queries(data["queries"], args.suite, eval_cfg)

    strategy = None if args.strategy == "vector" else args.strategy

    latencies: list[float] = []
    recalls = {k: [] for k in top_ks}
    mrrs: list[float] = []
    pos_empty = 0
    pos_total = 0
    neg_empty = 0
    neg_total = 0
    by_category: dict[str, list[float]] = {}
    positive_miss: list[dict] = []
    negative_fp: list[dict] = []
    per_query: list[dict] = []

    for q in queries:
        relevant = {id2name[d] for d in q.get("relevant_docs") or [] if d in id2name}
        hits, ms = search(args.rag_url, q["query"], max_k, strategy)
        latencies.append(ms)
        filtered = [h for h in hits if h.get("score", 0) >= min_score]
        top3_detail = [
            {"docName": h.get("docName"), "score": round(float(h.get("score", 0)), 4)}
            for h in filtered[:3]
        ]

        if relevant:
            pos_total += 1
            r3 = recall_at_k(filtered, relevant, 3, min_score)
            if not filtered:
                pos_empty += 1
            for k in top_ks:
                recalls[k].append(recall_at_k(filtered, relevant, k, min_score))
            mrr_val = mrr(filtered, relevant, min_score)
            mrrs.append(mrr_val)
            cat = q.get("category", "unknown")
            by_category.setdefault(cat, []).append(r3)
            if r3 < 1.0:
                positive_miss.append({
                    "id": q["id"],
                    "query": q["query"],
                    "category": cat,
                    "expected": sorted(relevant),
                    "top3": top3_detail,
                    "first_rank": first_relevant_rank(filtered, relevant, min_score),
                })
            per_query.append({
                "id": q["id"], "recall@3": r3, "mrr": round(mrr_val, 4), "latency_ms": round(ms, 1),
            })
        else:
            neg_total += 1
            if not filtered:
                neg_empty += 1
            else:
                negative_fp.append({
                    "id": q["id"],
                    "query": q["query"],
                    "top3": top3_detail,
                })
            per_query.append({"id": q["id"], "empty": len(filtered) == 0, "latency_ms": round(ms, 1)})

    report = {
        "run_at": run_at.isoformat(timespec="seconds"),
        "run_tag": tag,
        "date": run_at.date().isoformat(),
        "golden_version": data.get("version"),
        "suite": args.suite,
        "strategy": args.strategy,
        "query_count": len(queries),
        "min_score": min_score,
        "recall_at_k": {str(k): round(statistics.mean(recalls[k]), 4) for k in top_ks},
        "mrr": round(statistics.mean(mrrs) if mrrs else 0.0, 4),
        "empty_rate_positive": round(pos_empty / pos_total, 4) if pos_total else 0.0,
        "empty_rate_negative": round(neg_empty / neg_total, 4) if neg_total else 0.0,
        "latency_ms": {
            "p50": round(statistics.median(latencies), 1),
            "p95": round(sorted(latencies)[max(0, int(len(latencies) * 0.95) - 1)] if latencies else 0, 1),
        },
        "by_category_recall_at_3": {
            cat: round(statistics.mean(vals), 4) for cat, vals in by_category.items()
        },
        "production_gates": gates,
        "badcases": {
            "positive_miss": positive_miss,
            "negative_false_positive": negative_fp,
        },
        "per_query_summary": per_query,
    }
    gate_failures = check_gates(report, gates) if gates else []
    report["gate_check"] = {"passed": len(gate_failures) == 0, "failures": gate_failures}

    out_dir = ROOT / "docs/rag/reports"
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"baseline-{tag}.json"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "per_query_summary"}, ensure_ascii=False, indent=2))
    print(f"[OK] json report: {json_path}", file=sys.stderr)

    if args.report_md:
        md_path = out_dir / f"rag-eval-report-{tag}.md"
        write_markdown_report(report, md_path)
        print(f"[OK] markdown report: {md_path}", file=sys.stderr)

    if args.gate and gate_failures:
        print("[FAIL] production gates:", "; ".join(gate_failures), file=sys.stderr)
        return 1
    if gate_failures:
        print("[WARN] gates not met:", "; ".join(gate_failures), file=sys.stderr)
    else:
        print("[OK] production gates passed", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
