#!/usr/bin/env python3
"""rag_eval 门禁逻辑单测（无需 RAG 服务）。"""

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("rag_eval", ROOT / "scripts" / "rag_eval.py")
rag_eval = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(rag_eval)


def sample_report(recall5: float = 1.0, mrr: float = 0.95, p95: float = 400.0) -> dict:
    return {
        "recall_at_k": {"3": recall5, "5": recall5, "10": recall5},
        "mrr": mrr,
        "empty_rate_positive": 0.0,
        "empty_rate_negative": 1.0,
        "latency_ms": {"p50": 300.0, "p95": p95},
    }


class RagEvalGatesTest(unittest.TestCase):
    def test_check_gates_pass(self):
        gates = {"recall_at_5_min": 0.98, "mrr_min": 0.92, "empty_rate_negative_min": 0.95}
        self.assertEqual(rag_eval.check_gates(sample_report(), gates), [])

    def test_check_gates_recall5_fail(self):
        gates = {"recall_at_5_min": 0.98}
        fails = rag_eval.check_gates(sample_report(recall5=0.9), gates)
        self.assertTrue(any("recall@5" in f for f in fails))

    def test_cli_recall5_threshold(self):
        fails = rag_eval.check_cli_thresholds(sample_report(recall5=0.97), 0.98, None)
        self.assertEqual(len(fails), 1)

    def test_v6_improvement_pass(self):
        hybrid = sample_report(recall5=1.0, mrr=0.98)
        vector = sample_report(recall5=0.95, mrr=0.89)
        phase3 = {
            "recall_at_5_improvement_vs_vector_min": 0.15,
            "mrr_improvement_vs_vector_min": 0.1,
            "latency_p95_ms_max_hybrid_rerank": 800,
        }
        self.assertEqual(rag_eval.check_v6_improvement(hybrid, vector, phase3), [])

    def test_v6_improvement_mrr_fail(self):
        hybrid = sample_report(recall5=1.0, mrr=0.90)
        vector = sample_report(recall5=0.95, mrr=0.89)
        phase3 = {"mrr_improvement_vs_vector_min": 0.1, "latency_p95_ms_max_hybrid_rerank": 800}
        fails = rag_eval.check_v6_improvement(hybrid, vector, phase3)
        self.assertTrue(any("mrr improvement" in f for f in fails))

    def test_parse_rewrite_query_json(self):
        q = rag_eval.parse_rewrite_query('{"query":"公司差旅费报销管理办法"}', "报差旅")
        self.assertEqual(q, "公司差旅费报销管理办法")

    def test_parse_rewrite_query_skips_same_as_original(self):
        q = rag_eval.parse_rewrite_query('{"query":"报差旅"}', "报差旅")
        self.assertEqual(q, "")

    def test_parse_hyde_document_json(self):
        doc = rag_eval.parse_hyde_document(
            '{"document":"员工出差须提交审批单并保留发票。"}',
            480,
        )
        self.assertIn("出差", doc)

    def test_extract_json_blob(self):
        blob = rag_eval.extract_json_blob('说明 {"query":"x"} 尾部')
        self.assertEqual(json.loads(blob)["query"], "x")


if __name__ == "__main__":
    raise SystemExit(unittest.main())
