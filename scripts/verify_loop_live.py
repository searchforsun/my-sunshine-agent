#!/usr/bin/env python3
"""4.13.7 loop 容器 Live — 委托 verify_workflow_studio_live --suite loop。

用法:
  python3 scripts/verify_loop_live.py

前置:
  - 种子 knowledge-loop 已入库（init SQL 或 live INSERT）
  - workflow-manager / orchestrator / gateway / rag 已启动
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    cmd = [
        sys.executable,
        str(ROOT / "scripts" / "verify_workflow_studio_live.py"),
        "--suite",
        "loop",
    ]
    print("→", " ".join(cmd))
    return subprocess.call(cmd, cwd=str(ROOT))


if __name__ == "__main__":
    sys.exit(main())
