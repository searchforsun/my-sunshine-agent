#!/usr/bin/env python3
"""启动 Sunshine 核心服务链（配置来自 Nacos，见 docs/nacos、sync_nacos.py）。

用法:
  python scripts/start.py                         # 启动全链路（先 SIGKILL 旧进程）
  python scripts/start.py --restart               # 打包并重启全链路
  python scripts/start.py --restart orchestrator  # 打包并重启 orchestrator
"""
from __future__ import annotations

import argparse
import signal
import sys
import time

from sunshine_lib import ROOT, package_java_modules, skywalking_agent, start_java_detached, stop_java_service

# (服务名, 模块目录, JAR artifact, 端口)
SERVICES = [
    ("llm-gateway", "llm-gateway", "sunshine-llm-gateway", 8300),
    ("rag", "rag-service", "sunshine-rag", 8400),
    ("finance", "finance-service", "sunshine-finance", 8710),
    ("tool-manager", "tool-manager", "sunshine-tool-manager", 8210),
    ("skill-manager", "skill-manager", "sunshine-skill-manager", 8225),
    ("desensitize", "desensitize", "sunshine-desensitize", 8600),
    ("prompt", "prompt-manager", "sunshine-prompt", 8500),
    ("orchestrator", "orchestrator", "sunshine-orchestrator", 8200),
    ("auth", "auth-center", "sunshine-auth", 8100),
    ("bff", "bff", "sunshine-bff", 8001),
    ("gateway", "gateway", "sunshine-gateway", 8000),
]

SERVICE_BY_NAME = {name: (module, artifact, port) for name, module, artifact, port in SERVICES}


def resolve_targets(restart_only: list[str] | None) -> list[tuple[str, str, str, int]]:
    if not restart_only:
        return list(SERVICES)
    unknown = [n for n in restart_only if n not in SERVICE_BY_NAME]
    if unknown:
        known = ", ".join(sorted(SERVICE_BY_NAME))
        raise SystemExit(f"[FAIL] 未知服务: {', '.join(unknown)}；可选: {known}")
    return [(n, *SERVICE_BY_NAME[n]) for n in restart_only]


def start_service(name: str, module: str, artifact: str, port: int) -> object | None:
    print(f"Starting sunshine-{name} [Nacos config] ...")
    stop_java_service(module, artifact, port)
    if skywalking_agent().is_file():
        print("  SkyWalking agent enabled")
    return start_java_detached(module, artifact, service_name=name)


def main() -> int:
    parser = argparse.ArgumentParser(description="启动 / 重启 Sunshine 核心服务")
    parser.add_argument(
        "--restart",
        nargs="*",
        metavar="SERVICE",
        help="打包并重启指定服务（不指定则全链路）；启动前 SIGKILL 旧 PID",
    )
    args = parser.parse_args()

    targets = resolve_targets(args.restart)
    if args.restart is not None:
        label = ", ".join(n for n, *_ in targets)
        print(f"[RESTART] {label}")
        modules = [module for _, module, _, _ in targets]
        package_java_modules(modules)

    for _, module, _, _ in SERVICES:
        (ROOT / module / "logs").mkdir(parents=True, exist_ok=True)

    if not skywalking_agent().is_file():
        print("[INFO] SkyWalking agent not found — run: python scripts/download_skywalking_agent.py")

    procs = []
    for name, module, artifact, port in targets:
        procs.append(start_service(name, module, artifact, port))

    print("\n[OK] Core services started (Nacos config)")
    print("  LLM Gateway  :8300 | RAG :8400 | Finance :8710 | Tool Manager :8210 | Skill Manager :8225")
    print("  Desensitize  :8600 | Prompt :8500 | Orchestrator :8200")
    print("  Auth Center  :8100 | BFF :8001 | Gateway :8000")
    print("Live SkyWalking trace requires OAP at ecs4c16g:11800")

    if len(targets) == len(SERVICES):
        pids = [str(p.pid) for p in procs if p]
        print(f"Press Ctrl+C to stop — child PIDs: {', '.join(pids)}")

        def shutdown(*_args):
            print("\nStopping services ...")
            for p in procs:
                if p and p.poll() is None:
                    p.terminate()
            sys.exit(0)

        signal.signal(signal.SIGINT, shutdown)
        if hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, shutdown)

        try:
            while any(p and p.poll() is None for p in procs):
                time.sleep(1)
        except KeyboardInterrupt:
            shutdown()
    else:
        print(f"[OK] 已重启 {len(targets)} 个服务（单服务模式不阻塞，旧进程已 SIGKILL）")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FileNotFoundError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
