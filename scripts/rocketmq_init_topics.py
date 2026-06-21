#!/usr/bin/env python3
"""创建 RocketMQ topic（v5 Proxy 模式不自动建 topic，审计需 sunshine-audit）。"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

DEFAULT_TOPIC = "sunshine-audit"
DEFAULT_NAMESRV = os.environ.get("ROCKETMQ_NAMESRV_ADDR", "ecs4c16g:9876")
DEFAULT_BROKER = os.environ.get("ROCKETMQ_BROKER_ADDR", "ecs4c16g:10911")
DEFAULT_IMAGE = os.environ.get("ROCKETMQ_IMAGE", "apache/rocketmq:5.3.2")


def main() -> int:
    parser = argparse.ArgumentParser(description="RocketMQ 预创建 topic（经 mqadmin 直连 Broker）")
    parser.add_argument("--topic", default=DEFAULT_TOPIC)
    parser.add_argument("--namesrv", default=DEFAULT_NAMESRV)
    parser.add_argument("--broker", default=DEFAULT_BROKER)
    parser.add_argument("--image", default=DEFAULT_IMAGE)
    args = parser.parse_args()

    cmd = [
        "docker", "run", "--rm", args.image,
        "sh", "mqadmin", "updateTopic",
        "-n", args.namesrv,
        "-b", args.broker,
        "-t", args.topic,
        "-w", "8", "-r", "8", "-p", "6",
    ]
    print(f"[RUN] {' '.join(cmd)}")
    try:
        result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    except FileNotFoundError:
        print("[FAIL] 未找到 docker", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as e:
        print(f"[FAIL] mqadmin: {e.stderr or e.stdout}", file=sys.stderr)
        return 1
    if result.stdout.strip():
        print(result.stdout.strip())
    print(f"[OK] topic={args.topic} broker={args.broker}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
