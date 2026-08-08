#!/usr/bin/env python3
"""启动 / 停止 Sunshine 核心服务链（配置来自 Nacos，见 docs/nacos、sync_nacos.py）。

用法:
  python scripts/start.py                 # 启动全链路（先 SIGKILL 旧进程）
  python scripts/start.py --restart       # 打包并重启全链路
  python scripts/start.py --restart bff   # 打包并重启 bff
  python scripts/start.py --stop          # 停止全链路
  python scripts/start.py --stop bff      # 停止 bff

服务为独立进程（setsid），不随本脚本退出而关闭；停服请用 --stop。
"""
from __future__ import annotations

import argparse
import sys

from sunshine_lib import ROOT, package_java_modules, skywalking_agent, start_java_detached, stop_java_service

# (服务名, 模块目录, JAR artifact, 端口)
SERVICES = [
    ("llm-gateway", "llm-gateway", "sunshine-llm-gateway", 8300),
    ("rag", "rag-service", "sunshine-rag", 8400),
    ("biz-simulator", "biz-simulator", "sunshine-biz-simulator", 8700),
    ("tool-service", "tool-service", "sunshine-tool-service", 8210),
    ("resource-manager", "resource-manager", "sunshine-resource-manager", 8240),
    ("sandbox-service", "sandbox-service", "sunshine-sandbox-service", 8226),
    ("workflow-manager", "workflow-manager", "sunshine-workflow-manager", 8230),
    ("orchestrator", "orchestrator", "sunshine-orchestrator", 8200),
    ("auth", "auth-center", "sunshine-auth", 8100),
    ("bff", "bff", "sunshine-bff", 8001),
    ("gateway", "gateway", "sunshine-gateway", 8000),
]

SERVICE_BY_NAME = {name: (module, artifact, port) for name, module, artifact, port in SERVICES}


def resolve_targets(names: list[str] | None) -> list[tuple[str, str, str, int]]:
    if not names:
        return list(SERVICES)
    unknown = [n for n in names if n not in SERVICE_BY_NAME]
    if unknown:
        known = ", ".join(sorted(SERVICE_BY_NAME))
        raise SystemExit(f"[FAIL] 未知服务: {', '.join(unknown)}；可选: {known}")
    return [(n, *SERVICE_BY_NAME[n]) for n in names]


def start_service(name: str, module: str, artifact: str, port: int) -> None:
    print(f"Starting sunshine-{name} [Nacos config] ...")
    stop_java_service(module, artifact, port)
    if skywalking_agent().is_file():
        print("  SkyWalking agent enabled")
    start_java_detached(module, artifact, service_name=name)


def stop_service(name: str, module: str, artifact: str, port: int) -> None:
    print(f"Stopping sunshine-{name} ...")
    stop_java_service(module, artifact, port)


def main() -> int:
    parser = argparse.ArgumentParser(description="启动 / 停止 Sunshine 核心服务")
    parser.add_argument(
        "--restart",
        nargs="*",
        metavar="SERVICE",
        help="打包并重启指定服务（不指定则全链路）；启动前 SIGKILL 旧 PID",
    )
    parser.add_argument(
        "--stop",
        nargs="*",
        metavar="SERVICE",
        help="停止指定服务（不指定则全链路）",
    )
    args = parser.parse_args()

    if args.stop is not None:
        targets = resolve_targets(args.stop)
        for name, module, artifact, port in targets:
            stop_service(name, module, artifact, port)
        print(f"\n[OK] 已停止 {len(targets)} 个服务")
        return 0

    restart = args.restart is not None
    targets = resolve_targets(args.restart if restart else None)
    if restart:
        print(f"[RESTART] {', '.join(n for n, *_ in targets)}")
        package_java_modules([module for _, module, _, _ in targets])

    for _, module, _, _ in SERVICES:
        (ROOT / module / "logs").mkdir(parents=True, exist_ok=True)

    if not skywalking_agent().is_file():
        print("[INFO] SkyWalking agent not found — run: python scripts/download_skywalking_agent.py")

    for name, module, artifact, port in targets:
        start_service(name, module, artifact, port)

    print("\n[OK] Core services started (Nacos config)")
    print("  LLM Gateway  :8300 | RAG :8400 | Biz Simulator :8700 | Tool Service :8210 | Resource Manager :8240 | Sandbox :8226 | Workflow Manager :8230")
    print("  Orchestrator :8200 | Auth Center :8100 | BFF :8001 | Gateway :8000")
    print("Live SkyWalking trace requires OAP at ecs4c16g:11800")
    print(f"[OK] 已启动 {len(targets)} 个服务（独立进程，不随本脚本退出）；停服: python scripts/start.py --stop")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FileNotFoundError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
