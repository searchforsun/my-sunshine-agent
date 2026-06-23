#!/usr/bin/env python3
"""启动 Sunshine 核心服务链（配置来自 Nacos，见 docs/nacos、sync_nacos.py）。

用法:
  python scripts/start.py
"""
from __future__ import annotations

import signal
import sys
import time

from sunshine_lib import ROOT, skywalking_agent, start_java_detached

SERVICES = [
    ("llm-gateway", "llm-gateway", "sunshine-llm-gateway"),
    ("rag", "rag-service", "sunshine-rag"),
    ("finance", "finance-service", "sunshine-finance"),
    ("tool-manager", "tool-manager", "sunshine-tool-manager"),
    ("skill-manager", "skill-manager", "sunshine-skill-manager"),
    ("desensitize", "desensitize", "sunshine-desensitize"),
    ("prompt", "prompt-manager", "sunshine-prompt"),
    ("orchestrator", "orchestrator", "sunshine-orchestrator"),
    ("auth", "auth-center", "sunshine-auth"),
    ("bff", "bff", "sunshine-bff"),
    ("gateway", "gateway", "sunshine-gateway"),
]


def main() -> int:
    for module, _, _ in SERVICES:
        (ROOT / module / "logs").mkdir(parents=True, exist_ok=True)

    if not skywalking_agent().is_file():
        print("[INFO] SkyWalking agent not found — run: python scripts/download_skywalking_agent.py")

    procs = []
    for name, module, artifact in SERVICES:
        print(f"Starting sunshine-{name} [Nacos config] ...")
        if skywalking_agent().is_file():
            print("  SkyWalking agent enabled")
        procs.append(start_java_detached(module, artifact, service_name=name))

    print("\n[OK] Core services started (Nacos config)")
    print("  LLM Gateway  :8300 | RAG :8400 | Finance :8710 | Tool Manager :8210 | Skill Manager :8225")
    print("  Desensitize  :8600 | Prompt :8500 | Orchestrator :8200")
    print("  Auth Center  :8100 | BFF :8001 | Gateway :8000")
    print("Live SkyWalking trace requires OAP at ecs4c16g:11800")
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
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FileNotFoundError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
