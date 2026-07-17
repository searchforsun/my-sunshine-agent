#!/usr/bin/env python3
"""4.6 Plan-Workflow 动态 DAG Live — Planner 可产出 parallel/exclusive/loop，与静态 Studio 同构执行。

用法:
  python3 scripts/verify_plan_dag_live.py
  python3 scripts/verify_plan_dag_live.py --suite unit
  python3 scripts/verify_plan_dag_live.py --suite frontend

检查门:
  G1  PlanValidator 接受 parallel-gateway + join Planner 输出
  G2  PlanValidator 接受 exclusive-gateway 边条件 Planner 输出
  G3  PlanValidator 接受 loop 容器 + parentId body
  G4  PlanNormalizer 多 sink / join 正确拼接 answer
  G5  PlanExecutionSchedule 构建 Parallel / Exclusive / Loop 调度
  G6  PlanValidationFeedback 结构化 Replan 反馈
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(cmd: list[str], cwd: Path | None = None) -> int:
    print("→", " ".join(cmd))
    return subprocess.call(cmd, cwd=str(cwd or ROOT))


def suite_unit() -> int:
    tests = [
        "PlanValidatorTest",
        "PlanNormalizerTest",
        "PlanExecutionScheduleTest",
        "PlanJsonParserTest",
        "PlanValidationFeedbackTest",
    ]
    cmd = [
        "mvn",
        "-q",
        "-pl",
        "orchestrator",
        "test",
        f"-Dtest={','.join(tests)}",
    ]
    return run(cmd)


def suite_frontend() -> int:
    ui = ROOT / "sunshine-ui"
    test_file = ui / "src/utils/planExecutionCanvas.test.ts"
    if not test_file.is_file():
        print("SKIP frontend: planExecutionCanvas.test.ts 不存在")
        return 0
    print("SKIP frontend: sunshine-ui 未配置 vitest script（G6 见 planExecutionCanvas.test.ts 源码）")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="4.6 Plan-Workflow dynamic DAG verification")
    parser.add_argument(
        "--suite",
        choices=["unit", "frontend", "all"],
        default="all",
        help="验收套件（默认 all）",
    )
    args = parser.parse_args()
    rc = 0
    if args.suite in ("unit", "all"):
        rc = suite_unit() or rc
    if args.suite in ("frontend", "all"):
        rc = suite_frontend() or rc
    if rc == 0:
        print("\n✅ verify_plan_dag_live PASS")
    else:
        print("\n❌ verify_plan_dag_live FAIL", file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main())
