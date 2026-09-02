#!/usr/bin/env python3
"""Apply sunshine_biz schema + demo auth users to Live MySQL (idempotent).

行为:
  1. CREATE DATABASE IF NOT EXISTS sunshine_biz
  2. 整文件执行 docker/mysql/init/18-sunshine-biz.sql（同一 mysql 会话，避免按 ; 拆分）
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
    """按 ; 拆分 SQL（仅供 dry-run 计数/预览；apply 路径不使用）。"""
    statements: list[str] = []
    for raw in sql_text.split(";"):
        chunk = raw.strip()
        if not chunk:
            continue
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


def apply_live(*, host: str, port: int, user: str, password: str) -> None:
    """真实 apply：整文件喂 mysql，避免 split(';') 破坏会话/字面量。"""
    if not BIZ_SQL.is_file():
        raise FileNotFoundError(f"missing schema file: {BIZ_SQL}")
    biz_sql = BIZ_SQL.read_text(encoding="utf-8")
    auth_batch = ";\n".join(build_auth_inserts()) + ";\n"
    run_mysql(CREATE_DB + ";", host=host, port=port, user=user, password=password)
    run_mysql(biz_sql, host=host, port=port, user=user, password=password)
    run_mysql(auth_batch, host=host, port=port, user=user, password=password)


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

    if not BIZ_SQL.is_file():
        raise FileNotFoundError(f"missing schema file: {BIZ_SQL}")
    biz_text = BIZ_SQL.read_text(encoding="utf-8")
    biz_stmts = split_sql_statements(biz_text)
    auth_stmts = build_auth_inserts()
    preview = [CREATE_DB, *biz_stmts, *auth_stmts]

    print("Sunshine biz schema apply")
    print(f"  MySQL : {args.user}@{args.host}:{args.port}")
    print(f"  source: {BIZ_SQL.relative_to(ROOT)}")
    print(f"  statements (preview): {len(preview)} total")
    print(f"    - CREATE DATABASE: 1")
    print(f"    - from 18-sunshine-biz.sql: {len(biz_stmts)}")
    print(f"    - auth INSERT IGNORE: {len(auth_stmts)}")
    if not args.dry_run:
        print("  apply mode: whole-file batch (no semicolon split)")
    print()

    if args.dry_run:
        for i, stmt in enumerate(preview, 1):
            line = stmt.replace("\n", " ")
            if len(line) > 120:
                line = line[:117] + "..."
            print(f"  [{i:02d}] {line}")
        print()
        print("dry-run: no writes")
        return 0

    apply_live(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
    )
    print(">> applied CREATE DATABASE + 18-sunshine-biz.sql + auth users OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
