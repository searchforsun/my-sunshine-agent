#!/usr/bin/env python3
"""Detached 启动 demo-remote MCP mock（:8725）。

用法:
  python scripts/start_mcp_mock.py
  python scripts/start_mcp_mock.py --restart
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

from sunshine_lib import ROOT, stop_listening_port

LOG_DIR = ROOT / "logs"
PORT = 8725
SCRIPT = ROOT / "scripts" / "mcp_remote_mock.py"


def start_mock(*, restart: bool) -> int:
    if not SCRIPT.is_file():
        print(f"[FAIL] 未找到 {SCRIPT}", file=sys.stderr)
        return 1

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    if restart or stop_listening_port(PORT):
        print(f"  [KILL] :{PORT} released")

    stdout = open(LOG_DIR / "mcp-remote-mock.log", "w", encoding="utf-8")
    stderr = open(LOG_DIR / "mcp-remote-mock.err.log", "w", encoding="utf-8")
    proc = subprocess.Popen(
        [sys.executable, str(SCRIPT), "--host", "127.0.0.1", "--port", str(PORT)],
        cwd=str(ROOT),
        stdout=stdout,
        stderr=stderr,
        start_new_session=True,
    )
    time.sleep(1)
    if proc.poll() is not None:
        print("[FAIL] MCP mock 启动失败，查看 logs/mcp-remote-mock.err.log", file=sys.stderr)
        return 1

    print(f"[OK] demo-remote MCP mock started pid={proc.pid}")
    print(f"  Endpoint: http://127.0.0.1:{PORT}/mcp")
    print(f"  Logs:     {LOG_DIR}/mcp-remote-mock.log")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Detached 启动 demo-remote MCP mock")
    parser.add_argument("--restart", action="store_true", help="先释放 :8725 再启动")
    args = parser.parse_args()
    return start_mock(restart=args.restart)


if __name__ == "__main__":
    raise SystemExit(main())
