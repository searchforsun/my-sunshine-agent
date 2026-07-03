#!/usr/bin/env python3
"""rag_eval 门禁逻辑单测（无需 RAG 服务）。"""

from __future__ import annotations

import importlib.util
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
        gates = {
            "recallAt5Min": 0.98,
            "mrrMin": 0.92,
            "emptyRateNegativeMin": 0.95,
        }
        self.assertEqual(rag_eval.check_gates(sample_report(), gates), [])

    def test_check_gates_recall5_fail(self):
        gates = {"recallAt5Min": 0.98}
        fails = rag_eval.check_gates(sample_report(recall5=0.9), gates)
        self.assertTrue(any("Recall@5" in f for f in fails))

    def test_check_gates_p95_fail(self):
        gates = {"latencyP95MsMax": 500}
        fails = rag_eval.check_gates(sample_report(p95=900.0), gates)
        self.assertTrue(any("P95" in f for f in fails))


if __name__ == "__main__":
    raise SystemExit(unittest.main())
