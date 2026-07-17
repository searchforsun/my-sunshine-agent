#!/usr/bin/env python3
"""Detached 启动 sunshine-ui Vite dev server（不依赖 Cursor 后台终端）。

用法:
  python scripts/start_ui.py
  python scripts/start_ui.py --restart
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

from sunshine_lib import ROOT, stop_listening_port

UI_DIR = ROOT / "sunshine-ui"
LOG_DIR = UI_DIR / "logs"
PORT = 5173


def start_ui(*, restart: bool) -> int:
    if not (UI_DIR / "package.json").is_file():
        print(f"[FAIL] 未找到 {UI_DIR}/package.json", file=sys.stderr)
        return 1

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    if restart or stop_listening_port(PORT):
        print(f"  [KILL] :{PORT} released")

    stdout = open(LOG_DIR / "vite.log", "w", encoding="utf-8")
    stderr = open(LOG_DIR / "vite.err.log", "w", encoding="utf-8")
    proc = subprocess.Popen(
        ["npm", "run", "dev"],
        cwd=str(UI_DIR),
        stdout=stdout,
        stderr=stderr,
        start_new_session=True,
    )
    time.sleep(2)
    if proc.poll() is not None:
        print("[FAIL] Vite 启动失败，查看 sunshine-ui/logs/vite.err.log", file=sys.stderr)
        return 1

    print(f"[OK] sunshine-ui Vite started pid={proc.pid}")
    print(f"  Local: http://127.0.0.1:{PORT}/")
    print(f"  Logs:  {LOG_DIR}/vite.log")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Detached 启动 sunshine-ui")
    parser.add_argument("--restart", action="store_true", help="先释放 :5173 再启动")
    args = parser.parse_args()
    return start_ui(restart=args.restart)


if __name__ == "__main__":
    raise SystemExit(main())
