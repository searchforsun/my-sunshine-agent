#!/usr/bin/env python3
"""下载 SkyWalking Java Agent 到 docker/skywalking-agent/。

用法:
  python scripts/download_skywalking_agent.py
"""
from __future__ import annotations

import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

from sunshine_lib import ROOT

VERSION = "9.7.0"
URL = f"https://dlcdn.apache.org/skywalking/java-agent/{VERSION}/apache-skywalking-java-agent-{VERSION}.tgz"


def main() -> int:
    dest_dir = ROOT / "docker" / "skywalking-agent"
    jar_path = dest_dir / "skywalking-agent.jar"
    dest_dir.mkdir(parents=True, exist_ok=True)

    print(f"Downloading SkyWalking Java Agent {VERSION} ...")
    with tempfile.TemporaryDirectory() as tmp:
        tgz = Path(tmp) / f"apache-skywalking-java-agent-{VERSION}.tgz"
        urllib.request.urlretrieve(URL, tgz)
        extract = Path(tmp) / "extract"
        extract.mkdir()
        print("Extracting ...")
        with tarfile.open(tgz, "r:gz") as tf:
            tf.extractall(extract)
        found = next(extract.rglob("skywalking-agent.jar"), None)
        if not found:
            raise FileNotFoundError("skywalking-agent.jar not found in archive")
        shutil.copy2(found, jar_path)

    size_mb = jar_path.stat().st_size / (1024 * 1024)
    print(f"[OK] {jar_path} ({size_mb:.2f} MB)")
    print("Live trace requires OAP at ecs4c16g:11800 (see docker/skywalking-agent/README.md)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
