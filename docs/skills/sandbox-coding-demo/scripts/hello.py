#!/usr/bin/env python3
"""沙箱示例：打印问候与工作区提示。"""

from __future__ import annotations


def main() -> int:
    print("hello from /skill/scripts/hello.py")
    print("writable workspace is /workspace")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
