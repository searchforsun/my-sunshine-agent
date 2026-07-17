#!/usr/bin/env python3
"""沙箱示例：对 /workspace 下 CSV 做简单求和（无表头两列 id,amount）。"""

from __future__ import annotations

import csv
import sys
from pathlib import Path


def sum_amount(path: Path) -> float:
    total = 0.0
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            total += float(row.get("amount") or 0)
    return total


def main() -> int:
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "/workspace/sample.csv")
    if not target.is_file():
        print(f"missing file: {target}", file=sys.stderr)
        return 1
    print(f"sum(amount)={sum_amount(target):.2f} from {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
