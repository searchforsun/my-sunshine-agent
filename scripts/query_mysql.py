#!/usr/bin/env python3
"""Sunshine MySQL 统一查询工具。

用途：替代手工 `mysql -h ...` 命令行，给 AI / 运维一个可脚本化、可交互的查询入口。

用法:
  python3 scripts/query_mysql.py --sql "SELECT * FROM chat_message LIMIT 5"
  python3 scripts/query_mysql.py --database sunshine_chat --show-tables
  python3 scripts/query_mysql.py --sql "SELECT 1" --json
  python3 scripts/query_mysql.py            # 进入交互 REPL

交互模式指令:
  :q / :quit / :exit   退出
  :databases          列出所有数据库
  :use <db>           切换当前数据库
  :tables             列出当前数据库全部表

环境变量（默认值与 README 服务器中间件一致）:
  MYSQL_HOST 默认 ecs4c16g
  MYSQL_PORT 默认 3306
  MYSQL_USER 默认 root
  MYSQL_PASSWORD 默认 root123

安全: 默认只允许只读语句（SELECT/SHOW/DESC/EXPLAIN/WITH 等）；写语句（INSERT/UPDATE/DELETE/DROP/ALTER/CREATE/TRUNCATE/REPLACE/GRANT/REVOKE）
需显式加 --allow-write，避免误操作。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any

import pymysql

MYSQL_DEFAULTS = {
    "host": os.environ.get("MYSQL_HOST", "ecs4c16g"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

# 只读语句前缀白名单；显式写语句黑名单命中即需 --allow-write
READ_PREFIXES = ("select", "show", "describe", "desc", "explain", "with")
WRITE_KEYWORDS = (
    "insert", "update", "delete", "drop", "alter", "create",
    "truncate", "replace", "grant", "revoke", "rename", "call",
)

# 东亚全角字符计宽 2，用于表格对齐
_WIDE_CHARS = set("　，。、；：？！（）【】《》—…·～" +
                  "".join(chr(c) for c in range(0x4E00, 0x9FFF)))


def display_width(text: str) -> int:
    return sum(2 if ch in _WIDE_CHARS else 1 for ch in text)


def pad(text: str, width: int, align: str = "left") -> str:
    gaps = max(0, width - display_width(text))
    if align == "right":
        return " " * gaps + text
    return text + " " * gaps


def render_table(rows: list[tuple], cols: list[str]) -> str:
    """以对齐表格打印查询结果。"""
    if not rows:
        return "(0 rows)"
    headers = cols or [f"col{i}" for i in range(len(rows[0]))]
    widths = [display_width(str(h)) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            if i < len(widths):
                widths[i] = max(widths[i], display_width(str(cell)))
    sep = "+" + "+".join("-" * (w + 2) for w in widths) + "+"
    lines = [sep]
    lines.append("| " + " | ".join(pad(str(h), w) for h, w in zip(headers, widths)) + " |")
    lines.append(sep)
    for row in rows:
        cells = [pad(str(v), w) for v, w in zip(row, widths)]
        lines.append("| " + " | ".join(cells) + " |")
    lines.append(sep)
    lines.append(f"({len(rows)} rows)")
    return "\n".join(lines)


def render_csv(rows: list[tuple], cols: list[str]) -> str:
    import csv
    import io

    buf = io.StringIO()
    writer = csv.writer(buf)
    if cols:
        writer.writerow(cols)
    for row in rows:
        writer.writerow(["" if v is None else v for v in row])
    return buf.getvalue().rstrip("\n")


def render_json(rows: list[tuple], cols: list[str]) -> str:
    items = []
    for row in rows:
        item = {}
        for i, cell in enumerate(row):
            key = cols[i] if cols and i < len(cols) else f"col{i}"
            item[key] = cell
        items.append(item)
    return json.dumps(items, ensure_ascii=False, default=str)


def sanitize_sql(sql: str) -> str:
    """去掉注释后返回小写首词，用于只读校验。"""
    cleaned = []
    for line in sql.splitlines():
        line = line.split("--", 1)[0].split("#", 1)[0]
        cleaned.append(line)
    return " ".join(cleaned)


def is_readonly(sql: str) -> bool:
    normalized = sanitize_sql(sql).strip().lower()
    if not normalized:
        return True
    return normalized.startswith(READ_PREFIXES) and not any(
        keyword in normalized for keyword in WRITE_KEYWORDS
    )


def connect(args: argparse.Namespace, database: str | None = None) -> pymysql.Connection:
    return pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=database,
        charset="utf8mb4",
        connect_timeout=5,
        read_timeout=120,
    )


def execute(conn: pymysql.Connection, sql: str) -> tuple[list[tuple], list[str]]:
    with conn.cursor() as cur:
        cur.execute(sql)
        if cur.description is None:
            conn.commit()
            return [], [f"OK {cur.rowcount}"]
        cols = [d[0] for d in cur.description]
        rows = [tuple(row) for row in cur.fetchall()]
        return rows, cols


def run_once(conn: pymysql.Connection, args: argparse.Namespace, sql: str) -> int:
    if not is_readonly(sql) and not args.allow_write:
        print(f"[BLOCK] 写语句需显式 --allow-write: {sql.strip()[:120]}", file=sys.stderr)
        return 2
    rows, cols = execute(conn, sql)
    if args.json:
        print(render_json(rows, cols))
    elif args.csv:
        print(render_csv(rows, cols))
    elif cols and cols[0].startswith("OK"):
        print("OK")
    else:
        print(render_table(rows, cols))
    return 0


def list_databases(conn: pymysql.Connection, args: argparse.Namespace) -> None:
    sql = "SHOW DATABASES"
    rows, cols = execute(conn, sql)
    if args.json:
        print(render_json(rows, cols))
    elif args.csv:
        print(render_csv(rows, cols))
    else:
        print(render_table(rows, cols))


def list_tables(conn: pymysql.Connection, database: str, args: argparse.Namespace) -> None:
    sql = f"SHOW TABLES FROM `{database}`"
    with conn.cursor() as cur:
        cur.execute(sql)
        cols = [d[0] for d in cur.description]
        rows = [tuple(row) for row in cur.fetchall()]
    if args.json:
        print(render_json(rows, cols))
    elif args.csv:
        print(render_csv(rows, cols))
    else:
        print(render_table(rows, cols))


def repl(conn: pymysql.Connection, args: argparse.Namespace, database: str) -> None:
    print(f"Sunshine MySQL REPL — 连接 {args.host}:{args.port}")
    print(f"当前数据库: {database or '(未选择)'}   输入 :help 查看指令")
    while True:
        try:
            line = input("mysql> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            continue
        if line in (":q", ":quit", ":exit"):
            break
        if line == ":help":
            print(":q/:quit/:exit  退出\n:databases  列数据库\n:use <db>  切换库\n:tables  列当前库表")
            continue
        if line == ":databases":
            list_databases(conn, args)
            continue
        if line == ":tables":
            if not database:
                print("请先 :use <db> 选择数据库", file=sys.stderr)
                continue
            list_tables(conn, database, args)
            continue
        if line.startswith(":use "):
            target = line[5:].strip().strip("`")
            try:
                conn.select_db(target)
                database = target
                print(f"已切换 -> {database}")
            except Exception as exc:
                print(f"[FAIL] {exc}", file=sys.stderr)
            continue
        if not is_readonly(line) and not args.allow_write:
            print("[BLOCK] 写语句需 --allow-write（交互模式同样受保护）", file=sys.stderr)
            continue
        try:
            rows, cols = execute(conn, line)
            if cols and cols[0].startswith("OK"):
                print("OK")
            else:
                print(render_table(rows, cols))
        except Exception as exc:
            print(f"[ERROR] {exc}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=MYSQL_DEFAULTS["host"])
    parser.add_argument("--port", type=int, default=MYSQL_DEFAULTS["port"])
    parser.add_argument("--user", default=MYSQL_DEFAULTS["user"])
    parser.add_argument("--password", default=MYSQL_DEFAULTS["password"])
    parser.add_argument("--database", default=None, help="目标数据库（可省略，用 :use 切换）")
    parser.add_argument("--sql", default=None, help="待执行 SQL（提供则执行一次后退出）")
    parser.add_argument("--show-databases", action="store_true", help="列出所有数据库后退出")
    parser.add_argument("--show-tables", action="store_true", help="列出目标库全部表后退出")
    parser.add_argument("--json", action="store_true", help="JSON 输出")
    parser.add_argument("--csv", action="store_true", help="CSV 输出")
    parser.add_argument("--allow-write", action="store_true", help="允许写语句（INSERT/UPDATE/DELETE 等）")
    args = parser.parse_args()

    if args.json and args.csv:
        print("--json 与 --csv 互斥", file=sys.stderr)
        return 2

    try:
        conn = connect(args, args.database)
    except Exception as exc:
        print(f"[FAIL] MySQL 连接失败: {exc}", file=sys.stderr)
        return 1

    try:
        if args.show_databases:
            list_databases(conn, args)
            return 0
        if args.show_tables:
            if not args.database:
                print("--show-tables 需要 --database", file=sys.stderr)
                return 2
            list_tables(conn, args.database, args)
            return 0
        if args.sql:
            return run_once(conn, args, args.sql)
        repl(conn, args, args.database)
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
