#!/usr/bin/env python3
"""3.7 Grounding 验收 — 单测（AnswerGroundingChecker + 子 Agent 拦截）。"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UNIT_TESTS = (
    "AnswerGroundingCheckerTest",
    "GroundingEvidenceSupportTest",
    "AgentNodeHandlerTest",
)


def run_unit_tests() -> None:
    test_arg = ",".join(UNIT_TESTS)
    cmd = [
        "mvn", "test", "-pl", "orchestrator", "-am",
        f"-Dtest={test_arg}",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-q",
    ]
    print(f"[UNIT] {' '.join(cmd)}")
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if proc.stdout:
        tail = proc.stdout[-1500:] if len(proc.stdout) > 1500 else proc.stdout
        print(tail, end="")
    if proc.returncode != 0:
        if proc.stderr:
            print(proc.stderr[-1500:], file=sys.stderr)
        raise RuntimeError(f"单测失败 exit={proc.returncode}")
    print("[OK] 单测 PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--unit-only", action="store_true", help="仅跑单测（默认）")
    _ = parser.parse_args()
    run_unit_tests()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
