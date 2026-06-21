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


DEFAULT_RAG_REWRITE_PROMPT = """\
你是企业知识库检索 query 优化助手。用户问题将用于向量/混合检索。
请补全域内关键词（制度、流程、报销、请假、差旅、考勤等），标准化专有名词表述。
保留原意，不要编造事实；若已足够清晰则轻微润色即可。
只输出 JSON：{"query":"优化后的检索 query"}，不要 markdown 或其他文字。
"""

DEFAULT_HYDE_PROMPT = """\
你是企业知识库 HyDE 助手。根据用户问题，写一段**可能出现在企业制度/流程文档中**的中文段落，
用于向量检索匹配；不要写问答体，不要写「根据…规定」等元叙述，直接写制度条文式正文。
只引用常见域内概念（报销、差旅、请假、考勤、审批等），**禁止编造**具体金额/日期/人名。
只输出 JSON：{"document":"假想文档段落"}，不要 markdown 或其他文字。
"""


def load_rag_rewrite_cfg(orchestrator_yaml: Path) -> dict:
    if not orchestrator_yaml.is_file():
        return {"enabled": False}
    data = yaml.safe_load(orchestrator_yaml.read_text(encoding="utf-8")) or {}
    rag = ((data.get("agent") or {}).get("rewrite") or {}).get("rag") or {}
    if not rag.get("system-prompt") and not rag.get("systemPrompt"):
        rag = {**rag, "system-prompt": DEFAULT_RAG_REWRITE_PROMPT}
    return rag


def extract_json_blob(text: str) -> str:
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start : end + 1]
    return text


def parse_hyde_document(raw: str, max_chars: int = 480) -> str:
    if not raw or not raw.strip():
        return ""
    text = raw.strip()
    limit = max(80, max_chars)
    try:
        obj = json.loads(extract_json_blob(text))
        for field in ("document", "hyde", "passage", "text"):
            doc = str(obj.get(field, "")).strip()
            if doc:
                return doc[:limit].strip()
    except json.JSONDecodeError:
        pass
    plain = text
    if plain.startswith("```"):
        plain = plain.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    if plain.startswith("{"):
        return ""
    return plain[:limit].strip()


def parse_rewrite_query(raw: str, original_query: str) -> str:
    if not raw or not raw.strip():
        return ""
    text = raw.strip()
    try:
        obj = json.loads(extract_json_blob(text))
        q = str(obj.get("query", "")).strip()
        if q:
            return q if q != original_query.strip() else ""
    except json.JSONDecodeError:
        pass
    for line in text.splitlines():
        q = line.strip()
        if q.startswith("-"):
            q = q[1:].strip()
        if len(q) >= 2 and q != original_query.strip():
            return q
    return ""


def rewrite_rag_query(
    llm_url: str,
    api_key: str,
    model: str,
    system_prompt: str,
    original_query: str,
) -> tuple[str, bool, float]:
    t0 = time.perf_counter()
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    resp = requests.post(
        f"{llm_url.rstrip('/')}/v1/chat/completions",
        headers=headers,
        json={
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt.strip()},
                {"role": "user", "content": f"用户问题：{original_query.strip()}"},
            ],
            "stream": False,
        },
        timeout=60,
    )
    resp.raise_for_status()
    ms = (time.perf_counter() - t0) * 1000
    choices = resp.json().get("choices") or []
    content = ""
    if choices:
        content = (choices[0].get("message") or {}).get("content") or ""
    rewritten = parse_rewrite_query(content, original_query)
    if not rewritten:
        return original_query.strip(), False, ms
    applied = rewritten != original_query.strip()
    return rewritten, applied, ms


def hyde_rag_document(
    llm_url: str,
    api_key: str,
    model: str,
    system_prompt: str,
    original_query: str,
    max_chars: int,
) -> tuple[str, bool, float]:
    t0 = time.perf_counter()
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    resp = requests.post(
        f"{llm_url.rstrip('/')}/v1/chat/completions",
        headers=headers,
        json={
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt.strip()},
                {"role": "user", "content": f"用户问题：{original_query.strip()}"},
            ],
            "stream": False,
        },
        timeout=60,
    )
    resp.raise_for_status()
    ms = (time.perf_counter() - t0) * 1000
    choices = resp.json().get("choices") or []
    content = ""
    if choices:
        content = (choices[0].get("message") or {}).get("content") or ""
    document = parse_hyde_document(content, max_chars)
    if not document or document == original_query.strip():
        return original_query.strip(), False, ms
    return document, True, ms


def build_rewrite_report(
    queries: list[dict],
    id2name: dict[str, str],
    rag_url: str,
    llm_url: str,
    llm_api_key: str,
    rewrite_cfg: dict,
    strategy: str | None,
    top_k: int,
    min_score: float,
) -> dict:
    if not rewrite_cfg.get("enabled", True):
        return {
            "enabled": False,
            "message": "agent.rewrite.rag.enabled=false",
            "rows": [],
        }
    system_prompt = (
        rewrite_cfg.get("system-prompt")
        or rewrite_cfg.get("systemPrompt")
        or DEFAULT_RAG_REWRITE_PROMPT
    )
    model = rewrite_cfg.get("model") or "deepseek-v4-flash"
    hyde_cfg = rewrite_cfg.get("hyde") or {}
    hyde_enabled = bool(hyde_cfg.get("enabled", False))
    hyde_model = hyde_cfg.get("model") or model
    hyde_max_chars = int(hyde_cfg.get("max-chars") or hyde_cfg.get("maxChars") or 480)
    hyde_prompt = (
        hyde_cfg.get("system-prompt")
        or hyde_cfg.get("systemPrompt")
        or DEFAULT_HYDE_PROMPT
    )
    rows: list[dict] = []
    raw_recalls: list[float] = []
    rewritten_recalls: list[float] = []
    hyde_recalls: list[float] = []
    pipeline_recalls: list[float] = []
    latencies: list[float] = []
    hyde_latencies: list[float] = []
    for q in queries:
        raw_query = q["query"]
        try:
            rewritten, applied, rewrite_ms = rewrite_rag_query(
                llm_url, llm_api_key, model, system_prompt, raw_query
            )
        except requests.RequestException as exc:
            rows.append({
                "id": q["id"],
                "category": q.get("category"),
                "raw_query": raw_query,
                "rewritten_query": raw_query,
                "applied": False,
                "rewrite_latency_ms": 0.0,
                "error": str(exc),
            })
            continue
        latencies.append(rewrite_ms)
        row: dict = {
            "id": q["id"],
            "category": q.get("category"),
            "raw_query": raw_query,
            "rewritten_query": rewritten,
            "applied": applied,
            "rewrite_latency_ms": round(rewrite_ms, 1),
        }
        hyde_doc = ""
        hyde_applied = False
        pipeline_query = rewritten
        if hyde_enabled:
            try:
                hyde_doc, hyde_applied, hyde_ms = hyde_rag_document(
                    llm_url,
                    llm_api_key,
                    hyde_model,
                    hyde_prompt,
                    raw_query,
                    hyde_max_chars,
                )
                hyde_latencies.append(hyde_ms)
                row["hyde_document"] = hyde_doc
                row["hyde_applied"] = hyde_applied
                row["hyde_latency_ms"] = round(hyde_ms, 1)
                if hyde_applied:
                    pipeline_query = hyde_doc
            except requests.RequestException as exc:
                row["hyde_error"] = str(exc)
        row["pipeline_query"] = pipeline_query
        relevant = {id2name[d] for d in q.get("relevant_docs") or [] if d in id2name}
        if relevant:
            raw_hits, _ = search(rag_url, raw_query, top_k, strategy)
            rew_hits, _ = search(rag_url, rewritten, top_k, strategy)
            raw_filtered = [h for h in raw_hits if h.get("score", 0) >= min_score]
            rew_filtered = [h for h in rew_hits if h.get("score", 0) >= min_score]
            raw_r5 = recall_at_k(raw_filtered, relevant, 5, min_score)
            rew_r5 = recall_at_k(rew_filtered, relevant, 5, min_score)
            row["recall_at_5_raw"] = raw_r5
            row["recall_at_5_rewritten"] = rew_r5
            row["recall_at_5_delta"] = round(rew_r5 - raw_r5, 4)
            raw_recalls.append(raw_r5)
            rewritten_recalls.append(rew_r5)
            if hyde_enabled and hyde_applied:
                hyde_hits, _ = search(rag_url, hyde_doc, top_k, strategy)
                hyde_filtered = [h for h in hyde_hits if h.get("score", 0) >= min_score]
                hyde_r5 = recall_at_k(hyde_filtered, relevant, 5, min_score)
                row["recall_at_5_hyde"] = hyde_r5
                row["recall_at_5_hyde_delta"] = round(hyde_r5 - raw_r5, 4)
                hyde_recalls.append(hyde_r5)
            if pipeline_query != raw_query:
                pipe_hits, _ = search(rag_url, pipeline_query, top_k, strategy)
                pipe_filtered = [h for h in pipe_hits if h.get("score", 0) >= min_score]
                pipe_r5 = recall_at_k(pipe_filtered, relevant, 5, min_score)
                row["recall_at_5_pipeline"] = pipe_r5
                row["recall_at_5_pipeline_delta"] = round(pipe_r5 - raw_r5, 4)
                pipeline_recalls.append(pipe_r5)
        rows.append(row)
    applied_count = sum(1 for r in rows if r.get("applied"))
    summary = {
        "enabled": True,
        "model": model,
        "hyde_enabled": hyde_enabled,
        "query_count": len(rows),
        "applied_count": applied_count,
        "applied_rate": round(applied_count / len(rows), 4) if rows else 0.0,
        "rewrite_latency_ms": {
            "p50": round(statistics.median(latencies), 1) if latencies else 0.0,
            "p95": round(sorted(latencies)[max(0, int(len(latencies) * 0.95) - 1)], 1)
            if latencies
            else 0.0,
        },
    }
    if hyde_enabled:
        hyde_applied_count = sum(1 for r in rows if r.get("hyde_applied"))
        summary["hyde_model"] = hyde_model
        summary["hyde_applied_count"] = hyde_applied_count
        summary["hyde_applied_rate"] = round(hyde_applied_count / len(rows), 4) if rows else 0.0
        if hyde_latencies:
            summary["hyde_latency_ms"] = {
                "p50": round(statistics.median(hyde_latencies), 1),
                "p95": round(sorted(hyde_latencies)[max(0, int(len(hyde_latencies) * 0.95) - 1)], 1),
            }
    if raw_recalls:
        summary["recall_at_5_raw_mean"] = round(statistics.mean(raw_recalls), 4)
        summary["recall_at_5_rewritten_mean"] = round(statistics.mean(rewritten_recalls), 4)
        summary["recall_at_5_delta_mean"] = round(
            statistics.mean(rewritten_recalls) - statistics.mean(raw_recalls), 4
        )
        if hyde_recalls:
            summary["recall_at_5_hyde_mean"] = round(statistics.mean(hyde_recalls), 4)
            summary["recall_at_5_hyde_delta_mean"] = round(
                statistics.mean(hyde_recalls) - statistics.mean(raw_recalls), 4
            )
        if pipeline_recalls:
            summary["recall_at_5_pipeline_mean"] = round(statistics.mean(pipeline_recalls), 4)
            summary["recall_at_5_pipeline_delta_mean"] = round(
                statistics.mean(pipeline_recalls) - statistics.mean(raw_recalls), 4
            )
    return {"summary": summary, "rows": rows}


def write_rewrite_markdown(report: dict, path: Path) -> None:
    rewrite = report.get("rewrite_report") or {}
    summary = rewrite.get("summary") or {}
    rows = rewrite.get("rows") or []
    lines = [
        f"# QueryRewrite 评测 — {report.get('run_at', report.get('date'))}",
        "",
        f"> suite={report.get('suite')} · strategy={report.get('strategy')} · "
        f"run={report.get('run_tag')}",
        "",
    ]
    if not summary.get("enabled", True):
        lines.append(f"改写未启用：{rewrite.get('message', 'disabled')}")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return
    lines.extend([
        "## 汇总",
        "",
        f"- 改写模型：`{summary.get('model')}`",
        f"- HyDE：{'开启' if summary.get('hyde_enabled') else '关闭'}"
        + (f"（`{summary.get('hyde_model')}`）" if summary.get('hyde_enabled') else ""),
        f"- 样本数：{summary.get('query_count')} · rag 改写：{summary.get('applied_count')} "
        f"({summary.get('applied_rate')})",
        f"- 改写 P50/P95：{summary.get('rewrite_latency_ms', {}).get('p50')} / "
        f"{summary.get('rewrite_latency_ms', {}).get('p95')} ms",
    ])
    if summary.get("hyde_enabled"):
        lines.append(
            f"- HyDE 生成：{summary.get('hyde_applied_count')} "
            f"({summary.get('hyde_applied_rate')}) · "
            f"P50/P95 {summary.get('hyde_latency_ms', {}).get('p50')} / "
            f"{summary.get('hyde_latency_ms', {}).get('p95')} ms"
        )
    if "recall_at_5_raw_mean" in summary:
        lines.append(
            f"- 正例 Recall@5：raw={summary['recall_at_5_raw_mean']} → "
            f"rewritten={summary['recall_at_5_rewritten_mean']} "
            f"(Δ {summary.get('recall_at_5_delta_mean')})"
        )
        if "recall_at_5_hyde_mean" in summary:
            lines.append(
                f"- HyDE Recall@5：{summary['recall_at_5_hyde_mean']} "
                f"(Δ raw {summary.get('recall_at_5_hyde_delta_mean')})"
            )
        if "recall_at_5_pipeline_mean" in summary:
            lines.append(
                f"- 生产链路 Recall@5（HyDE 优先）：{summary['recall_at_5_pipeline_mean']} "
                f"(Δ raw {summary.get('recall_at_5_pipeline_delta_mean')})"
            )
    hyde_on = summary.get("hyde_enabled")
    lines.extend(["", "## raw vs rewritten vs HyDE", ""])
    if not rows:
        lines.append("无样本。")
    else:
        if hyde_on:
            lines.append(
                "| id | rag改写 | raw | rewritten | HyDE片段 | Δrag | ΔHyDE | Δ链路 |"
            )
            lines.append("|----|---------|-----|-----------|---------|------|-------|-------|")
        else:
            lines.append("| id | applied | raw_query | rewritten_query | Δ recall@5 | 耗时 ms |")
            lines.append("|----|---------|-----------|-----------------|------------|---------|")
        for r in rows:
            if hyde_on:
                hyde_clip = str(r.get("hyde_document") or "—").replace("|", "\\|")
                if len(hyde_clip) > 48:
                    hyde_clip = hyde_clip[:45] + "..."
                lines.append(
                    f"| {r.get('id')} | {r.get('applied')} | "
                    f"{str(r.get('raw_query', '')).replace('|', '\\|')} | "
                    f"{str(r.get('rewritten_query', '')).replace('|', '\\|')} | "
                    f"{hyde_clip} | "
                    f"{r.get('recall_at_5_delta', '—')} | "
                    f"{r.get('recall_at_5_hyde_delta', '—')} | "
                    f"{r.get('recall_at_5_pipeline_delta', '—')} |"
                )
            else:
                delta = r.get("recall_at_5_delta")
                delta_s = "—" if delta is None else str(delta)
                raw_q = r.get("raw_query", "").replace("|", "\\|")
                rew_q = r.get("rewritten_query", "").replace("|", "\\|")
                lines.append(
                    f"| {r.get('id')} | {r.get('applied')} | {raw_q} | {rew_q} | {delta_s} | "
                    f"{r.get('rewrite_latency_ms', '—')} |"
                )
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


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


def check_cli_thresholds(report: dict, recall5_min: float | None, mrr_min: float | None) -> list[str]:
    """CLI 显式阈值（--fail-if-recall5-below / --fail-if-mrr-below）。"""
    failures: list[str] = []
    if recall5_min is not None:
        v = report["recall_at_k"]["5"]
        if v < recall5_min:
            failures.append(f"recall@5 {v} < {recall5_min} (cli)")
    if mrr_min is not None:
        if report["mrr"] < mrr_min:
            failures.append(f"mrr {report['mrr']} < {mrr_min} (cli)")
    return failures


def check_v6_improvement(hybrid_report: dict, vector_report: dict, phase3_gates: dict) -> list[str]:
    """v6 提升轨：hybrid+rerank 相对 vector 基线的 MRR / Recall 提升。"""
    failures: list[str] = []
    if not phase3_gates:
        return failures
    mrr_h = float(hybrid_report["mrr"])
    mrr_v = float(vector_report["mrr"])
    rel_mrr = (mrr_h - mrr_v) / max(mrr_v, 1e-6)
    mrr_floor = float(phase3_gates.get("mrr_improvement_vs_vector_min", 0.1))
    if rel_mrr < mrr_floor:
        failures.append(f"v6 mrr improvement {rel_mrr:.4f} < {mrr_floor} vs vector")
    r5_h = float(hybrid_report["recall_at_k"]["5"])
    r5_v = float(vector_report["recall_at_k"]["5"])
    rel_r5 = (r5_h - r5_v) / max(r5_v, 1e-6)
    abs_r5 = r5_h - r5_v
    r5_floor = float(phase3_gates.get("recall_at_5_improvement_vs_vector_min", 0.15))
    # 相对 +15% 或绝对 +5pp（难例集 vector 上限约 0.95 时仍可达标）
    if rel_r5 < r5_floor and abs_r5 < 0.05:
        failures.append(
            f"v6 recall@5 improvement rel={rel_r5:.4f} abs={abs_r5:.4f} "
            f"(need rel>={r5_floor} or abs>=0.05) vs vector"
        )
    p95_max = float(phase3_gates.get("latency_p95_ms_max_hybrid_rerank", 800))
    if hybrid_report["latency_ms"]["p95"] > p95_max:
        failures.append(f"v6 p95 {hybrid_report['latency_ms']['p95']}ms > {p95_max}ms")
    return failures


def merge_gate_failures(*parts: list[str]) -> list[str]:
    seen: set[str] = set()
    merged: list[str] = []
    for part in parts:
        for item in part:
            if item not in seen:
                seen.add(item)
                merged.append(item)
    return merged


def write_regression_report(report: dict, path: Path) -> None:
    """追加 CI / 本地回归摘要到 docs/rag/regression-YYYY-MM-DD.md。"""
    gate = report.get("gate_check") or {}
    status = "PASS" if gate.get("passed") else "FAIL"
    block = [
        f"## {report.get('run_at')} — {report.get('suite')} / {report.get('strategy')}",
        "",
        f"- run_tag: `{report.get('run_tag')}`",
        f"- queries: {report.get('query_count')}",
        f"- Recall@5: {report['recall_at_k']['5']} · MRR: {report['mrr']}",
        f"- 正例 Empty: {report['empty_rate_positive']} · 负例 Empty: {report['empty_rate_negative']}",
        f"- P95: {report['latency_ms']['p95']} ms",
        f"- **门禁: {status}**",
    ]
    if report.get("v6_improvement"):
        imp = report["v6_improvement"]
        block.append(
            f"- v6 vs vector: recall@5 {imp.get('recall5_delta')} "
            f"(rel {imp.get('recall5_rel')}) · mrr delta {imp.get('mrr_delta')} (rel {imp.get('mrr_rel')})"
        )
    if gate.get("failures"):
        for f in gate["failures"]:
            block.append(f"- FAIL: {f}")
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
        path.write_text("\n".join(header + block) + "\n", encoding="utf-8")


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
    rewrite = report.get("rewrite_report")
    if rewrite and (rewrite.get("summary") or {}).get("enabled"):
        rs = rewrite["summary"]
        lines.append("")
        lines.append("## QueryRewrite（rag）")
        lines.append("")
        lines.append(
            f"- 改写率：{rs.get('applied_count')}/{rs.get('query_count')} "
            f"({rs.get('applied_rate')}) · P95 改写耗时 {rs.get('rewrite_latency_ms', {}).get('p95')} ms"
        )
        if "recall_at_5_delta_mean" in rs:
            lines.append(
                f"- 正例 Recall@5 均值：raw {rs.get('recall_at_5_raw_mean')} → "
                f"rewritten {rs.get('recall_at_5_rewritten_mean')} "
                f"(Δ {rs.get('recall_at_5_delta_mean')})"
            )
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
    parser.add_argument(
        "--fail-if-recall5-below",
        type=float,
        default=None,
        metavar="FLOAT",
        help="Recall@5 低于该值时 exit 1（可与 --gate 叠加）",
    )
    parser.add_argument(
        "--fail-if-mrr-below",
        type=float,
        default=None,
        metavar="FLOAT",
        help="MRR 低于该值时 exit 1",
    )
    parser.add_argument(
        "--compare-vector-json",
        default="",
        help="v6 提升轨：与 vector 基线 JSON 对比（hybrid+rerank 时启用 phase3_v6_gates）",
    )
    parser.add_argument("--report-md", action="store_true", help="输出 Markdown 报告")
    parser.add_argument(
        "--regression-md",
        action="store_true",
        help="追加 docs/rag/regression-YYYY-MM-DD.md 回归摘要",
    )
    parser.add_argument(
        "--ci",
        action="store_true",
        help="CI 模式：--gate --report-md --regression-md",
    )
    parser.add_argument("--tag", default="", help="报告文件名附加标记（可选，如 v5-smoke）")
    parser.add_argument(
        "--rewrite-report",
        action="store_true",
        help="输出 raw_query vs rewritten_query 对比（调用 LLM Gateway rag 改写 + 可选 Recall 对比）",
    )
    parser.add_argument(
        "--rewrite-only",
        action="store_true",
        help="仅跑改写对比报告（跳过常规 RAG 指标评测）",
    )
    parser.add_argument(
        "--llm-url",
        default=os.environ.get("LLM_URL", "http://localhost:8300"),
        help="LLM Gateway 基址（改写调用）",
    )
    parser.add_argument(
        "--llm-api-key",
        default=os.environ.get("LLM_API_KEY", ""),
        help="LLM Gateway API Key（可选）",
    )
    parser.add_argument(
        "--orchestrator-yaml",
        default=str(ROOT / "docs/nacos/sunshine-orchestrator.yaml"),
        help="读取 agent.rewrite.rag 配置的 SSOT 副本",
    )
    args = parser.parse_args()
    if args.ci:
        args.gate = True
        args.report_md = True
        args.regression_md = True

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

    rewrite_report: dict | None = None
    if args.rewrite_report or args.rewrite_only:
        rewrite_cfg = load_rag_rewrite_cfg(Path(args.orchestrator_yaml))
        rewrite_report = build_rewrite_report(
            queries,
            id2name,
            args.rag_url,
            args.llm_url,
            args.llm_api_key,
            rewrite_cfg,
            strategy,
            max(top_ks),
            min_score,
        )
        print("[OK] rewrite report rows:", len(rewrite_report.get("rows") or []), file=sys.stderr)

    if args.rewrite_only:
        report = {
            "run_at": run_at.isoformat(timespec="seconds"),
            "run_tag": tag,
            "date": run_at.date().isoformat(),
            "golden_version": data.get("version"),
            "suite": args.suite,
            "strategy": args.strategy,
            "query_count": len(queries),
            "rewrite_report": rewrite_report,
        }
        out_dir = ROOT / "docs/rag/reports"
        out_dir.mkdir(parents=True, exist_ok=True)
        json_path = out_dir / f"rewrite-report-{tag}.json"
        json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        md_path = out_dir / f"rewrite-report-{tag}.md"
        write_rewrite_markdown(report, md_path)
        print(json.dumps(rewrite_report.get("summary") or {}, ensure_ascii=False, indent=2))
        print(f"[OK] json: {json_path}", file=sys.stderr)
        print(f"[OK] markdown: {md_path}", file=sys.stderr)
        return 0

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
    if rewrite_report is not None:
        report["rewrite_report"] = rewrite_report
    prod_failures = check_gates(report, gates) if gates else []
    cli_failures = check_cli_thresholds(report, args.fail_if_recall5_below, args.fail_if_mrr_below)
    v6_failures: list[str] = []
    if args.compare_vector_json:
        vector_path = Path(args.compare_vector_json)
        if not vector_path.is_file():
            print(f"[FAIL] vector baseline not found: {vector_path}", file=sys.stderr)
            return 1
        vector_report = json.loads(vector_path.read_text(encoding="utf-8"))
        phase3 = eval_cfg.get("phase3_v6_gates") or {}
        v6_failures = check_v6_improvement(report, vector_report, phase3)
        mrr_v = float(vector_report["mrr"])
        mrr_h = float(report["mrr"])
        r5_v = float(vector_report["recall_at_k"]["5"])
        r5_h = float(report["recall_at_k"]["5"])
        report["v6_improvement"] = {
            "vector_run_tag": vector_report.get("run_tag"),
            "recall5_delta": round(r5_h - r5_v, 4),
            "recall5_rel": round((r5_h - r5_v) / max(r5_v, 1e-6), 4),
            "mrr_delta": round(mrr_h - mrr_v, 4),
            "mrr_rel": round((mrr_h - mrr_v) / max(mrr_v, 1e-6), 4),
        }
    all_failures = merge_gate_failures(prod_failures, cli_failures, v6_failures)
    report["gate_check"] = {"passed": len(all_failures) == 0, "failures": all_failures}

    exit_failures: list[str] = []
    if args.gate:
        exit_failures = merge_gate_failures(exit_failures, prod_failures)
        if args.compare_vector_json:
            exit_failures = merge_gate_failures(exit_failures, v6_failures)
    if args.fail_if_recall5_below is not None or args.fail_if_mrr_below is not None:
        exit_failures = merge_gate_failures(exit_failures, cli_failures)

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
        if rewrite_report is not None:
            rewrite_md = out_dir / f"rewrite-report-{tag}.md"
            write_rewrite_markdown(report, rewrite_md)
            print(f"[OK] rewrite markdown: {rewrite_md}", file=sys.stderr)

    if args.regression_md:
        reg_path = ROOT / "docs/rag" / f"regression-{run_at.date().isoformat()}.md"
        write_regression_report(report, reg_path)
        print(f"[OK] regression report: {reg_path}", file=sys.stderr)

    should_fail = len(exit_failures) > 0
    if should_fail:
        print("[FAIL] production gates:", "; ".join(exit_failures), file=sys.stderr)
        return 1
    if all_failures:
        print("[WARN] gates not met:", "; ".join(all_failures), file=sys.stderr)
    else:
        print("[OK] production gates passed", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
