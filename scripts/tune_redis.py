#!/usr/bin/env python3
"""Redis 内存治理：扩容 maxmemory 并切换淘汰策略。

背景：认证会话（sa-token-redis-jackson）与长任务事件流（sunshine:gen:*:events、
agentscope:state、llm:cache）共用同一 Redis 实例。256MB + allkeys-lru 时长任务
写入风暴会驱逐登录态键，前端 401 被误判为登录过期登出。本脚本幂等可重复执行。

用法:
  python scripts/tune_redis.py             # 应用扩容配置
  python scripts/tune_redis.py --show      # 仅查看当前配置
"""
from __future__ import annotations

import argparse
import socket
import sys

HOST = "ecs4c16g"
PORT = 6379
PASSWORD = "redis123"
# 30GB 物理内存的机器，4GB 上限仍留足余量；noeviction 时写满直接报错而非静默丢键
TARGET_MAXMEMORY_BYTES = 4 * 1024 * 1024 * 1024
TARGET_POLICY = "noeviction"


def redis_command(*args: str) -> str:
    def enc(parts: tuple[str, ...]) -> bytes:
        return (
            f"*{len(parts)}\r\n".encode()
            + b"".join(f"${len(p)}\r\n{p}\r\n".encode() for p in parts)
        )

    with socket.create_connection((HOST, PORT), timeout=5) as sock:
        sock.sendall(enc(("AUTH", PASSWORD)))
        sock.recv(256)
        sock.sendall(enc(args))
        chunks = []
        while True:
            try:
                part = sock.recv(65536)
            except socket.timeout:
                break
            if not part:
                break
            chunks.append(part)
        return b"".join(chunks).decode(errors="replace")


def show() -> None:
    print(redis_command("CONFIG", "GET", "maxmemory"))
    print(redis_command("CONFIG", "GET", "maxmemory-policy"))
    info = redis_command("INFO", "memory")
    for line in info.splitlines():
        if line.startswith(("used_memory:", "used_memory_peak:", "maxmemory")):
            print(line)
    stats = redis_command("INFO", "stats")
    for line in stats.splitlines():
        if line.startswith("evicted_keys"):
            print(line)


def apply() -> None:
    for args in (
        ("CONFIG", "SET", "maxmemory", str(TARGET_MAXMEMORY_BYTES)),
        ("CONFIG", "SET", "maxmemory-policy", TARGET_POLICY),
    ):
        reply = redis_command(*args).strip()
        print(f">> {' '.join(args[1:])} -> {reply}")
        if reply != "+OK":
            print("配置失败，中止", file=sys.stderr)
            sys.exit(1)
    drift = check_container_args()
    print("当前配置：")
    show()
    if drift:
        print(
            "\n[警告] smt-redis 容器启动参数与目标配置不一致（docker inspect 可查），"
            "容器重启后会回退。执行容器重建：\n"
            "  docker stop smt-redis && docker rm smt-redis && docker run -d --name smt-redis \\\n"
            "    --restart unless-stopped --network docker_smt-net \\\n"
            "    --network-alias smt-redis --network-alias redis -p 6379:6379 \\\n"
            "    -v docker_redis-data:/data redis:7.2-alpine \\\n"
            "    redis-server --requirepass redis123 --maxmemory 4gb "
            "--maxmemory-policy noeviction --appendonly yes",
            file=sys.stderr,
        )
        sys.exit(2)


def check_container_args() -> bool:
    """检测 smt-redis 容器 Cmd 里的 maxmemory/policy 是否与目标一致（无 docker 视为一致）。"""
    try:
        import json
        import subprocess
    except ImportError:
        return False
    try:
        raw = subprocess.run(
            ["docker", "inspect", "smt-redis", "--format", "{{json .Config.Cmd}}"],
            capture_output=True, text=True, timeout=10,
        ).stdout
        cmd = json.loads(raw)
    except Exception:
        return False
    want_mem = f"--maxmemory {TARGET_MAXMEMORY_BYTES // (1024 * 1024)}mb"
    joined = " ".join(cmd)
    ok_mem = want_mem in joined
    ok_policy = f"--maxmemory-policy {TARGET_POLICY}" in joined
    if not (ok_mem and ok_policy):
        print(f"[检查] 容器 Cmd={joined}")
        return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Redis 内存治理（扩容 + 关闭业务驱逐）")
    parser.add_argument("--show", action="store_true", help="仅查看当前配置")
    args = parser.parse_args()
    if args.show:
        show()
    else:
        apply()
    return 0


if __name__ == "__main__":
    sys.exit(main())
