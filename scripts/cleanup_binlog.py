#!/usr/bin/env python3
"""每日 binlog 清理：PURGE 1 天前的 binlog，防止磁盘被 binlog 累积撑满。

背景：2026-08-21 磁盘 100%（MySQL binlog 5 天累积 ~30G）。已调 binlog_expire_logs_seconds=86400，
但 expire 仅在新 binlog 写入时触发，binlog 量大/低峰时可能滞后；本脚本每日兜底 PURGE，
并可选 FLUSH LOGS 触发 rotate。

用法:
  python3 scripts/cleanup_binlog.py            # 仅 PURGE 1 天前 binlog
  python3 scripts/cleanup_binlog.py --flush    # PURGE 后再 FLUSH LOGS（rotate 新 binlog）
  cron: 0 3 * * * cd /usr/local/gitproj/my-sunshine-agent && python3 scripts/cleanup_binlog.py --flush

环境变量:
  MYSQL_HOST      默认 ecs4c16g
  MYSQL_PORT      默认 3306
  MYSQL_USER      默认 root
  MYSQL_PASSWORD  默认 root123
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import sys

import pymysql

MYSQL_DEFAULTS = {
    "host": os.environ.get("MYSQL_HOST", "ecs4c16g"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}
RETENTION_DAYS = int(os.environ.get("BINLOG_RETENTION_DAYS", "1"))


def connect() -> pymysql.Connection:
    return pymysql.connect(
        host=MYSQL_DEFAULTS["host"],
        port=MYSQL_DEFAULTS["port"],
        user=MYSQL_DEFAULTS["user"],
        password=MYSQL_DEFAULTS["password"],
        connect_timeout=5,
        read_timeout=120,
    )


def purge_binlogs(conn: pymysql.Connection, retention_days: int) -> tuple[int, int]:
    """PURGE 截止到 now-retention_days 的 binlog；返回 (保留数, 已清数)。"""
    cur = conn.cursor()
    cur.execute("SHOW BINARY LOGS")
    logs = [row[0] for row in cur.fetchall()]
    total = len(logs)
    if total == 0:
        return 0, 0
    cutoff = (dt.datetime.now() - dt.timedelta(days=retention_days)).strftime("%Y-%m-%d %H:%M:%S")
    cur.execute(
        "PURGE BINARY LOGS BEFORE %s",
        (cutoff,),
    )
    cur.execute("SHOW BINARY LOGS")
    remaining = len(cur.fetchall())
    cur.close()
    return remaining, total - remaining


def flush_logs(conn: pymysql.Connection) -> None:
    cur = conn.cursor()
    cur.execute("FLUSH LOGS")
    cur.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flush", action="store_true", help="PURGE 后再 FLUSH LOGS 触发 rotate")
    parser.add_argument("--days", type=int, default=RETENTION_DAYS, help="保留天数，默认 1")
    args = parser.parse_args()

    try:
        conn = connect()
    except Exception as exc:
        print(f"[FAIL] MySQL 连接失败: {exc}")
        return 1

    try:
        remaining, purged = purge_binlogs(conn, args.days)
        if args.flush and purged > 0:
            flush_logs(conn)
        print(f"[OK] PURGE {purged} 个 binlog，剩余 {remaining} 个（保留 {args.days} 天）")
        return 0
    except Exception as exc:
        print(f"[FAIL] PURGE 失败: {exc}")
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
