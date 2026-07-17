#!/usr/bin/env python3
"""Build sunshine-sandbox-python:3.11-slim from docker/sandbox."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ctx = ROOT / "docker" / "sandbox"
cmd = ["docker", "build", "-t", "sunshine-sandbox-python:3.11-slim", str(ctx)]
print("+", " ".join(cmd), flush=True)
sys.exit(subprocess.call(cmd))
