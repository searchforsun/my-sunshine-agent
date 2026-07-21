#!/usr/bin/env python3
"""Apply sunshine_biz schema + demo auth users to Live MySQL (idempotent).

行为:
  1. CREATE DATABASE IF NOT EXISTS sunshine_biz
  2. 执行 docker/mysql/init/18-sunshine-biz.sql（按 ; 拆分；跳过空/纯注释）
  3. INSERT IGNORE 三演示用户到 sunshine_auth.sys_user（alice/bob/carol）

用法:
  python3 scripts/apply_sunshine_biz_schema.py --dry-run
  python3 scripts/apply_sunshine_biz_schema.py
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from sunshine_lib import run_mysql  # noqa: E402

BIZ_SQL = ROOT / "docker" / "mysql" / "init" / "18-sunshine-biz.sql"

CREATE_DB = (
    "CREATE DATABASE IF NOT EXISTS sunshine_biz "
    "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
)

# 与 docker/mysql/init/10-sunshine-auth.sql 演示用户一致
DEMO_PASSWORD_HASH = (
    "$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62"
)
DEMO_USERS = (
    ("a1111111-1111-4111-a111-111111111111", "alice", "爱丽丝"),
    ("b2222222-2222-4222-b222-222222222222", "bob", "鲍勃"),
    ("c3333333-3333-4333-c333-333333333333", "carol", "卡罗尔"),
)

MYSQL_DEFAULTS = {
    "host": "ecs4c16g",
    "port": 3306,
    "user": "root",
    "password": "root123",
}


def split_sql_statements(sql_text: str) -> list[str]:
    """按 ; 拆分 SQL，跳过空语句与纯注释块。"""
    statements: list[str] = []
    for raw in sql_text.split(";"):
        chunk = raw.strip()
        if not chunk:
            continue
        # 去掉逐行 -- 注释后若无实质内容则跳过
        meaningful: list[str] = []
        for line in chunk.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("--"):
                continue
            meaningful.append(stripped)
        if not meaningful:
            continue
        statements.append("\n".join(meaningful))
    return statements


def build_auth_inserts() -> list[str]:
    stmts: list[str] = []
    for uid, username, nickname in DEMO_USERS:
        stmts.append(
            "INSERT IGNORE INTO sunshine_auth.sys_user "
            "(id, username, password_hash, nickname, status, created_at, updated_at, "
            "tenant_id, default_write_hitl_mode) VALUES "
            f"('{uid}', '{username}', '{DEMO_PASSWORD_HASH}', '{nickname}', 1, "
            "'2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never')"
        )
    return stmts


def collect_statements() -> list[str]:
    if not BIZ_SQL.is_file():
        raise FileNotFoundError(f"missing schema file: {BIZ_SQL}")
    biz_stmts = split_sql_statements(BIZ_SQL.read_text(encoding="utf-8"))
    return [CREATE_DB, *biz_stmts, *build_auth_inserts()]


def apply_statements(
    statements: list[str],
    *,
    host: str,
    port: int,
    user: str,
    password: str,
) -> None:
    # 整批经 mysql client 执行，保证 USE sunshine_biz 等会话上下文连贯
    batch = ";\n".join(statements) + ";\n"
    run_mysql(batch, host=host, port=port, user=user, password=password)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Apply sunshine_biz schema + demo auth users to Live MySQL"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print statement counts / preview and exit without writing",
    )
    parser.add_argument("--host", default=MYSQL_DEFAULTS["host"])
    parser.add_argument("--port", type=int, default=MYSQL_DEFAULTS["port"])
    parser.add_argument("--user", default=MYSQL_DEFAULTS["user"])
    parser.add_argument("--password", default=MYSQL_DEFAULTS["password"])
    args = parser.parse_args()

    statements = collect_statements()
    biz_count = len(split_sql_statements(BIZ_SQL.read_text(encoding="utf-8")))
    auth_count = len(build_auth_inserts())

    print("Sunshine biz schema apply")
    print(f"  MySQL : {args.user}@{args.host}:{args.port}")
    print(f"  source: {BIZ_SQL.relative_to(ROOT)}")
    print(f"  statements: {len(statements)} total")
    print(f"    - CREATE DATABASE: 1")
    print(f"    - from 18-sunshine-biz.sql: {biz_count}")
    print(f"    - auth INSERT IGNORE: {auth_count}")
    print()

    if args.dry_run:
        for i, stmt in enumerate(statements, 1):
            preview = stmt.replace("\n", " ")
            if len(preview) > 120:
                preview = preview[:117] + "..."
            print(f"  [{i:02d}] {preview}")
        print()
        print("dry-run: no writes")
        return 0

    apply_statements(
        statements,
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
    )
    print(f">> applied {len(statements)} statements OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
