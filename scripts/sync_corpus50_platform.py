#!/usr/bin/env python3
"""将 Live MySQL 中 Workflow / Expert / Skill 的旧 finance 工具 Catalog ID 改写为 corpus-50 新短名。

OLD→NEW 映射（仅本脚本注释保留旧名，业务路径禁止残留）:
  sdk__sunshine-finance__list_finance_messages      → list_my_expenses
  sdk__sunshine-finance__get_finance_message_detail → get_expense_detail
  sdk__sunshine-finance__summarize_finance_by_status → summarize_my_expenses

用法:
  python3 scripts/sync_corpus50_platform.py --dry-run
  python3 scripts/sync_corpus50_platform.py

可选: 同步后打印 tool-manager 启用新工具的指引；若 Gateway 可达且提供
--token，则尝试 PATCH 启用新工具并禁用旧工具。
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

# 注释用映射表（旧短名 → 新 Catalog ID）；业务代码不得引用旧短名
_TOOL_ID_MAP = {
    "sdk__sunshine-finance__list_finance_messages": "sdk__sunshine-finance__list_my_expenses",
    "sdk__sunshine-finance__get_finance_message_detail": "sdk__sunshine-finance__get_expense_detail",
    "sdk__sunshine-finance__summarize_finance_by_status": "sdk__sunshine-finance__summarize_my_expenses",
}

NEW_TOOL_IDS = tuple(_TOOL_ID_MAP.values())
OLD_TOOL_IDS = tuple(_TOOL_ID_MAP.keys())

MYSQL_DEFAULTS = {
    "host": "ecs4c16g",
    "port": 3306,
    "user": "root",
    "password": "root123",
}


def mysql_query(sql: str, *, host: str, port: int, user: str, password: str) -> str:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [
            mysql,
            "-h",
            host,
            "-P",
            str(port),
            "-u",
            user,
            f"-p{password}",
            "-N",
            "-B",
            "-e",
            sql,
        ],
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")
    return proc.stdout or ""


def build_replace_expr(column: str) -> str:
    expr = column
    for old, new in _TOOL_ID_MAP.items():
        expr = f"REPLACE({expr}, '{old}', '{new}')"
    return expr


def count_hits(db: str, table: str, columns: list[str], *, host: str, port: int, user: str, password: str) -> int:
    conds = " OR ".join(
        f"{col} LIKE '%{old}%'" for col in columns for old in OLD_TOOL_IDS
    )
    out = mysql_query(
        f"SELECT COUNT(*) FROM {db}.{table} WHERE {conds};",
        host=host,
        port=port,
        user=user,
        password=password,
    )
    return int((out.strip() or "0").splitlines()[0])


def update_table(
    db: str,
    table: str,
    columns: list[str],
    *,
    dry_run: bool,
    host: str,
    port: int,
    user: str,
    password: str,
) -> int:
    before = count_hits(db, table, columns, host=host, port=port, user=user, password=password)
    print(f"[{db}.{table}] rows containing old tool IDs: {before}")
    if before == 0 or dry_run:
        return before
    sets = ", ".join(f"{col} = {build_replace_expr(col)}" for col in columns)
    conds = " OR ".join(
        f"{col} LIKE '%{old}%'" for col in columns for old in OLD_TOOL_IDS
    )
    mysql_query(
        f"UPDATE {db}.{table} SET {sets} WHERE {conds};",
        host=host,
        port=port,
        user=user,
        password=password,
    )
    after = count_hits(db, table, columns, host=host, port=port, user=user, password=password)
    print(f"[{db}.{table}] after UPDATE remaining: {after}")
    return before


def print_tool_enable_instructions() -> None:
    print("\n== tool-manager 启用指引 ==")
    print("1) 确认 finance-service / oa-service / hr-biz-service 已启动并完成 SDK sync")
    print("2) 在 /tools 或 Admin API 启用新工具：")
    for tid in NEW_TOOL_IDS:
        print(f"   PATCH /api/admin/tools/{tid}  {{\"enabled\": true}}")
    print("3) 禁用并删除 Catalog 中旧三工具（若仍存在）：")
    for tid in OLD_TOOL_IDS:
        print(f"   PATCH /api/admin/tools/{tid}  {{\"enabled\": false}}  # then delete if UI supports")
    print("4) 将新工具加入 global-react-default 等工具集；重启 orchestrator 刷新缓存")
    print("5) 可选: python3 scripts/verify_tool_integration_live.py --suite sdk")


def try_enable_via_api(gateway: str, token: str, dry_run: bool) -> None:
    try:
        import requests
    except ImportError:
        print("[warn] requests 未安装，跳过 API 启用")
        return
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    for app in ("sunshine-finance", "sunshine-oa", "sunshine-hr"):
        url = f"{gateway.rstrip('/')}/api/admin/tools/sdk-applications/{app}/sync"
        print(f"{'[dry-run] ' if dry_run else ''}POST {url}")
        if not dry_run:
            resp = requests.post(url, headers=headers, timeout=60)
            print(f"  sync {app}: {resp.status_code}")
    for tid in NEW_TOOL_IDS:
        url = f"{gateway.rstrip('/')}/api/admin/tools/{tid}"
        print(f"{'[dry-run] ' if dry_run else ''}PATCH {url} enabled=true")
        if not dry_run:
            resp = requests.patch(url, headers=headers, json={"enabled": True}, timeout=30)
            print(f"  enable {tid}: {resp.status_code}")
    for tid in OLD_TOOL_IDS:
        url = f"{gateway.rstrip('/')}/api/admin/tools/{tid}"
        print(f"{'[dry-run] ' if dry_run else ''}PATCH {url} enabled=false")
        if not dry_run:
            resp = requests.patch(url, headers=headers, json={"enabled": False}, timeout=30)
            print(f"  disable {tid}: {resp.status_code} (404 ok if already gone)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync corpus-50 tool IDs into Live MySQL")
    parser.add_argument("--dry-run", action="store_true", help="只统计，不写库")
    parser.add_argument("--host", default=MYSQL_DEFAULTS["host"])
    parser.add_argument("--port", type=int, default=MYSQL_DEFAULTS["port"])
    parser.add_argument("--user", default=MYSQL_DEFAULTS["user"])
    parser.add_argument("--password", default=MYSQL_DEFAULTS["password"])
    parser.add_argument("--gateway", default="http://127.0.0.1:8000", help="Gateway base for optional API")
    parser.add_argument("--token", default="", help="若提供，则尝试 sync+enable 新工具 / disable 旧工具")
    args = parser.parse_args()

    print("OLD→NEW map:")
    for o, n in _TOOL_ID_MAP.items():
        print(f"  {o}\n    → {n}")

    targets = [
        ("sunshine_workflow", "workflow_version", ["plan_json", "catalog_meta"]),
        ("sunshine_expert", "expert_definition", ["tools_json"]),
        ("sunshine_skill", "skill_version", ["tools_json"]),
    ]
    total = 0
    for db, table, cols in targets:
        total += update_table(
            db,
            table,
            cols,
            dry_run=args.dry_run,
            host=args.host,
            port=args.port,
            user=args.user,
            password=args.password,
        )

    if args.dry_run:
        print(f"\n[dry-run] would touch ~{total} row(s) across tables; no writes performed")
    else:
        print(f"\nDone. rows that had old IDs before rewrite: {total}")

    print_tool_enable_instructions()
    if args.token:
        try_enable_via_api(args.gateway, args.token, args.dry_run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
