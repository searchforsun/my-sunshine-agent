#!/usr/bin/env python3
"""清空 Sunshine 会话与三层上下文：对话历史 + L1 + L2 + L3。

用法:
  python scripts/clear_session_cache.py --force
  python scripts/clear_session_cache.py --force --restart-orchestrator
  python scripts/clear_session_cache.py --force --include-audit

默认范围（不改 Nacos 配置）:
  MySQL  chat_message / chat_conversation / conversation_context_l1(L1) / user_context_state(L2)
  Milvus sunshine_chat_history（L3 对话向量）
  Redis  sunshine:stm:* / sunshine:gen:* / sunshine:user:*

可选:
  --include-audit     额外 TRUNCATE chat_audit_log
  --skip-l2 / --skip-l3  跳过对应层（排障用）
  --restart-orchestrator  重启 orchestrator + llm-gateway

浏览器 localStorage 需在 DevTools 控制台执行脚本末尾 JS。
前端傻瓜式验收见: docs/context/verify-l1-l2-l3-frontend.md
"""
from __future__ import annotations

import argparse
import subprocess
import sys

from sunshine_lib import (
    BROWSER_LOCALSTORAGE_JS,
    redis_delete_patterns,
    run_mysql,
    start_java_detached,
    stop_java_service,
)

# sunshine:stm:* 仅清遗留 Redis 键（旧 STM 运行时已删除）
REDIS_PATTERNS = ["sunshine:stm:*", "sunshine:gen:*", "sunshine:user:*"]
L3_COLLECTION = "sunshine_chat_history"


def build_mysql_sql(
    database: str,
    *,
    include_audit: bool,
    include_l2: bool,
) -> str:
    lines = [
        f"USE {database};",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "TRUNCATE TABLE chat_message;",
        "TRUNCATE TABLE chat_conversation;",
        "TRUNCATE TABLE conversation_context_l1;",
    ]
    if include_audit:
        lines.append("TRUNCATE TABLE chat_audit_log;")
    if include_l2:
        lines.append("TRUNCATE TABLE user_context_state;")
    lines.append("SET FOREIGN_KEY_CHECKS = 1;")
    return "\n".join(lines)


def ensure_pymilvus() -> None:
    try:
        import pymilvus  # noqa: F401
    except ImportError:
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "pymilvus", "-q"],
            check=True,
        )


def wipe_l3_chat_history(*, host: str, port: int, collection: str = L3_COLLECTION) -> str:
    """清空 L3 collection 全部实体（不 drop，rag-service 无需重启）。"""
    ensure_pymilvus()
    from pymilvus import Collection, connections, utility

    alias = "sunshine_clear_l3"
    try:
        connections.connect(alias=alias, host=host, port=str(port))
        if not utility.has_collection(collection, using=alias):
            return f"collection '{collection}' 不存在，跳过"
        col = Collection(collection, using=alias)
        col.load()
        # auto_id Int64 主键：删光全部行
        col.delete(expr="id >= 0")
        col.flush()
        return f"已清空 collection '{collection}'"
    finally:
        try:
            connections.disconnect(alias)
        except Exception:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Clear Sunshine session + L1/L2/L3 context",
    )
    parser.add_argument("--force", action="store_true", help="Skip confirmation")
    parser.add_argument("--include-audit", action="store_true")
    parser.add_argument(
        "--include-l2",
        "--include-ltm",
        dest="include_l2_legacy",
        action="store_true",
        help="兼容旧参数：L2 现已默认清理，无需再传",
    )
    parser.add_argument(
        "--skip-l2",
        action="store_true",
        help="跳过 TRUNCATE user_context_state（排障）",
    )
    parser.add_argument(
        "--skip-l3",
        action="store_true",
        help="跳过 Milvus sunshine_chat_history（排障）",
    )
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
    parser.add_argument("--milvus-host", default="ecs4c16g")
    parser.add_argument("--milvus-port", type=int, default=19530)
    args = parser.parse_args()

    include_l2 = not args.skip_l2
    include_l3 = not args.skip_l3

    print("\nSunshine session / L1·L2·L3 cleanup")
    print(f"  MySQL : {args.mysql_user}@{args.mysql_host}:{args.mysql_port}/{args.mysql_database}")
    print(f"  Redis : {args.redis_host}:{args.redis_port}")
    print(f"  Milvus: {args.milvus_host}:{args.milvus_port}/{L3_COLLECTION}")
    print("  scope : chat_message + chat_conversation")
    print("         + conversation_context_l1 (L1)")
    if include_l2:
        print("         + user_context_state (L2)")
    else:
        print("         · skip L2")
    if include_l3:
        print("         + sunshine_chat_history (L3)")
    else:
        print("         · skip L3")
    if args.include_audit:
        print("         + chat_audit_log")
    if args.include_l2_legacy and include_l2:
        print("  note  : --include-l2 已默认生效，可省略")
    print(f"  Redis : {' / '.join(REDIS_PATTERNS)}")
    print()

    if not args.force:
        answer = input("Confirm cleanup? [y/N] ").strip().lower()
        if answer not in ("y", "yes"):
            print("Cancelled.")
            return 0

    print(">> Cleaning MySQL (对话 + L1" + (" + L2" if include_l2 else "") + ")...")
    run_mysql(
        build_mysql_sql(
            args.mysql_database,
            include_audit=args.include_audit,
            include_l2=include_l2,
        ),
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_password,
    )
    print("   MySQL done")

    if include_l3:
        print(">> Cleaning Milvus L3 (sunshine_chat_history)...")
        detail = wipe_l3_chat_history(host=args.milvus_host, port=args.milvus_port)
        print(f"   L3 {detail}")
    else:
        print(">> Skip L3 Milvus wipe")

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
    print("\n前端验收步骤: docs/context/verify-l1-l2-l3-frontend.md")
    print("All done.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
