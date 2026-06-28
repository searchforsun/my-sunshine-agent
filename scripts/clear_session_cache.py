#!/usr/bin/env python3
"""清空 Sunshine 会话数据：MySQL 对话/记忆表 + Redis STM/生成流缓存。

用法:
  python scripts/clear_session_cache.py
  python scripts/clear_session_cache.py --force
  python scripts/clear_session_cache.py --force --restart-orchestrator
  python scripts/clear_session_cache.py --include-audit --include-ltm

--restart-orchestrator 会一并重启 orchestrator :8200 与 llm-gateway :8300。
浏览器 localStorage 需在 DevTools 控制台执行脚本末尾给出的 JS。
"""
from __future__ import annotations

import argparse
import sys

from sunshine_lib import (
    BROWSER_LOCALSTORAGE_JS,
    redis_delete_patterns,
    run_mysql,
    start_java_detached,
    stop_java_service,
)

REDIS_PATTERNS = ["sunshine:stm:*", "sunshine:gen:*", "sunshine:user:*"]


def build_mysql_sql(
    database: str,
    *,
    include_audit: bool,
    include_ltm: bool,
) -> str:
    lines = [
        f"USE {database};",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "TRUNCATE TABLE chat_message;",
        "TRUNCATE TABLE chat_conversation;",
        "TRUNCATE TABLE conversation_memory_mtm;",
    ]
    if include_audit:
        lines.append("TRUNCATE TABLE chat_audit_log;")
    if include_ltm:
        lines.append("TRUNCATE TABLE user_memory_profile;")
    lines.append("SET FOREIGN_KEY_CHECKS = 1;")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Clear Sunshine session / memory cache")
    parser.add_argument("--force", action="store_true", help="Skip confirmation")
    parser.add_argument("--include-audit", action="store_true")
    parser.add_argument("--include-ltm", action="store_true")
    parser.add_argument(
        "--restart-orchestrator",
        action="store_true",
        help="Restart orchestrator + llm-gateway JVM cache",
    )
    parser.add_argument("--mysql-host", default="ecs4c16g")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="root123")
    parser.add_argument("--mysql-database", default="sunshine_chat")
    parser.add_argument("--redis-host", default="ecs4c16g")
    parser.add_argument("--redis-port", type=int, default=6379)
    parser.add_argument("--redis-password", default="redis123")
    args = parser.parse_args()

    print("\nSunshine session / memory cache cleanup")
    print(f"  MySQL : {args.mysql_user}@{args.mysql_host}:{args.mysql_port}/{args.mysql_database}")
    print(f"  Redis : {args.redis_host}:{args.redis_port}")
    print("  scope : chat_* + conversation_memory_mtm")
    if args.include_audit:
        print("         + chat_audit_log")
    if args.include_ltm:
        print("         + user_memory_profile (LTM)")
    print(f"  Redis : {' / '.join(REDIS_PATTERNS)}")
    print()

    if not args.force:
        answer = input("Confirm cleanup? [y/N] ").strip().lower()
        if answer not in ("y", "yes"):
            print("Cancelled.")
            return 0

    print(">> Cleaning MySQL session tables...")
    run_mysql(
        build_mysql_sql(
            args.mysql_database,
            include_audit=args.include_audit,
            include_ltm=args.include_ltm,
        ),
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_password,
    )
    print("   MySQL done")

    print(">> Cleaning Redis session cache...")
    total = redis_delete_patterns(
        args.redis_host,
        args.redis_port,
        args.redis_password,
        REDIS_PATTERNS,
    )
    print(f"   Redis total deleted: {total} keys")

    if args.restart_orchestrator:
        print(">> Restarting orchestrator + llm-gateway...")
        stop_java_service("llm-gateway", "sunshine-llm-gateway", 8300)
        stop_java_service("orchestrator", "sunshine-orchestrator", 8200)
        start_java_detached("llm-gateway", "sunshine-llm-gateway", service_name="llm-gateway", wait_sec=6)
        print("   llm-gateway started (8300)")
        start_java_detached("orchestrator", "sunshine-orchestrator", service_name="orchestrator", wait_sec=6)
        print("   orchestrator started (8200)")
    else:
        print("\nTip: add --restart-orchestrator to restart orchestrator + llm-gateway JVM cache.")

    print("\nBrowser (sunshine-ui DevTools console):")
    print(BROWSER_LOCALSTORAGE_JS)
    print("\nAll done.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
