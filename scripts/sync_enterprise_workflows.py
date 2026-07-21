#!/usr/bin/env python3
"""将 enterprise_workflow_plans 同步到 Live sunshine_workflow。

用法:
  python3 scripts/sync_enterprise_workflows.py --dry-run
  python3 scripts/sync_enterprise_workflows.py
  python3 scripts/sync_enterprise_workflows.py --write-sql

环境:
  MYSQL_HOST 默认 ecs4c16g；MYSQL_PORT=3306；MYSQL_USER=root；MYSQL_PASSWORD=root123
  REDIS_HOST 默认 ecs4c16g（PUBLISH workflow-catalog-changed）
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from enterprise_workflow_plans import TENANT, WORKFLOWS, dumps_meta, dumps_plan  # noqa: E402

SQL_PATH = ROOT / "docker" / "mysql" / "init" / "13-sunshine-workflow-manager.sql"
DB = "sunshine_workflow"

MYSQL_DEFAULTS = {
    "host": os.environ.get("MYSQL_HOST", "ecs4c16g"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}
REDIS_HOST = os.environ.get("REDIS_HOST", "ecs4c16g")
REDIS_PASSWORD = os.environ.get("REDIS_PASSWORD", "redis123")


def sql_escape(value: str) -> str:
    """Escape for MySQL single-quoted string literals."""
    return value.replace("\\", "\\\\").replace("'", "\\'")


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


def mysql_exec_file(sql_path: Path, *, host: str, port: int, user: str, password: str) -> None:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    with sql_path.open("r", encoding="utf-8") as fh:
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
                DB,
            ],
            stdin=fh,
            text=True,
            capture_output=True,
        )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")


def next_version(workflow_id: str, *, host: str, port: int, user: str, password: str) -> int:
    out = mysql_query(
        f"SELECT COALESCE(MAX(version),0)+1 FROM {DB}.workflow_version "
        f"WHERE tenant_id='{sql_escape(TENANT)}' AND workflow_id='{sql_escape(workflow_id)}';",
        host=host,
        port=port,
        user=user,
        password=password,
    )
    return int((out.strip() or "1").splitlines()[0])


def build_upsert_sql(wf: dict, version: int) -> str:
    wid = wf["id"]
    display = wf["displayName"]
    desc = wf.get("description") or ""
    mode = wf.get("mode") or "workflow"
    plan = dumps_plan(wf["plan"])
    meta = dumps_meta(wf["catalogMeta"])
    lines = [
        f"INSERT INTO workflow_definition "
        f"(tenant_id, id, display_name, description, mode, enabled, active_version, source) "
        f"VALUES ('{sql_escape(TENANT)}', '{sql_escape(wid)}', '{sql_escape(display)}', "
        f"'{sql_escape(desc)}', '{sql_escape(mode)}', 1, {version}, 'seed') "
        f"ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), "
        f"description=VALUES(description), enabled=1, source='seed';",
        f"INSERT INTO workflow_version "
        f"(tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) "
        f"VALUES ('{sql_escape(TENANT)}', '{sql_escape(wid)}', {version}, 'published', "
        f"'{sql_escape(plan)}', '{sql_escape(meta)}', NOW());",
        f"UPDATE workflow_definition SET active_version={version} "
        f"WHERE tenant_id='{sql_escape(TENANT)}' AND id='{sql_escape(wid)}';",
    ]
    return "\n".join(lines)


def sync_live(*, dry_run: bool, host: str, port: int, user: str, password: str) -> None:
    print(f"=== Sync enterprise workflows === MySQL={host}:{port} dry_run={dry_run}")
    statements: list[str] = []
    for wf in WORKFLOWS:
        wid = wf["id"]
        ver = next_version(wid, host=host, port=port, user=user, password=password)
        print(f"  UPSERT {wid} -> v{ver}")
        statements.append(build_upsert_sql(wf, ver))
    if dry_run:
        print(f"\n[dry-run] planned {len(WORKFLOWS)} UPSERT(s); no writes performed")
        return
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        suffix=".sql",
        delete=False,
    ) as tmp:
        tmp.write("SET NAMES utf8mb4;\n")
        tmp.write("\n".join(statements))
        tmp.write("\n")
        tmp_path = Path(tmp.name)
    try:
        mysql_exec_file(tmp_path, host=host, port=port, user=user, password=password)
    finally:
        tmp_path.unlink(missing_ok=True)
    print(f"[OK] wrote {len(WORKFLOWS)} workflow(s) to {DB}")
    publish_catalog_changed()


def publish_catalog_changed() -> None:
    redis_cli = shutil.which("redis-cli")
    if redis_cli:
        proc = subprocess.run(
            [
                redis_cli,
                "-h",
                REDIS_HOST,
                "-a",
                REDIS_PASSWORD,
                "--no-auth-warning",
                "PUBLISH",
                "workflow-catalog-changed",
                TENANT,
            ],
            text=True,
            capture_output=True,
        )
        if proc.returncode == 0:
            print(f"[OK] redis-cli PUBLISH workflow-catalog-changed {TENANT}")
            return
        print(f"[warn] redis-cli PUBLISH failed: {proc.stderr or proc.stdout}")
    try:
        import redis as redis_lib

        client = redis_lib.Redis(host=REDIS_HOST, port=6379, password=REDIS_PASSWORD)
        client.publish("workflow-catalog-changed", TENANT)
        print(f"[OK] redis PUBLISH workflow-catalog-changed {TENANT} (python)")
    except Exception as exc:  # noqa: BLE001 — best-effort notify
        print(f"[warn] redis PUBLISH failed: {exc}")


def build_seed_inserts() -> str:
    """Seed file always uses version=1."""
    chunks: list[str] = []
    for wf in WORKFLOWS:
        wid = wf["id"]
        display = wf["displayName"]
        desc = wf.get("description") or ""
        mode = wf.get("mode") or "workflow"
        plan = dumps_plan(wf["plan"])
        meta = dumps_meta(wf["catalogMeta"])
        chunks.append(f"-- {wid}")
        chunks.append(
            f"INSERT INTO workflow_definition "
            f"(tenant_id, id, display_name, description, mode, enabled, active_version, source) "
            f"VALUES ('{sql_escape(TENANT)}', '{sql_escape(wid)}', '{sql_escape(display)}', "
            f"'{sql_escape(desc)}', '{sql_escape(mode)}', 1, 1, 'seed');"
        )
        chunks.append(
            f"INSERT INTO workflow_version "
            f"(tenant_id, workflow_id, version, status, plan_json, catalog_meta, published_at) "
            f"VALUES ('{sql_escape(TENANT)}', '{sql_escape(wid)}', 1, 'published', "
            f"'{sql_escape(plan)}', '{sql_escape(meta)}', CURRENT_TIMESTAMP);"
        )
        chunks.append("")
    return "\n".join(chunks).rstrip() + "\n"


def write_sql() -> None:
    text = SQL_PATH.read_text(encoding="utf-8")
    # Keep DDL / header; replace from seed comment or first INSERT onward
    m = re.search(
        r"(?m)^-- 标杆 workflow 种子.*\n|^INSERT INTO workflow_definition\b",
        text,
    )
    if not m:
        raise RuntimeError(f"cannot find INSERT/seed section in {SQL_PATH}")
    header = text[: m.start()]
    if not header.rstrip().endswith(";"):
        # ensure blank line before seed block
        header = header.rstrip() + "\n\n"
    seed_header = (
        "-- 标杆 workflow 种子（published v1，plan_json 含 layout；source=seed；"
        "节点 id = {type}-{8位hex}；由 scripts/sync_enterprise_workflows.py --write-sql 生成）\n\n"
    )
    body = build_seed_inserts()
    SQL_PATH.write_text(header + seed_header + body, encoding="utf-8")
    print(f"[OK] wrote {len(WORKFLOWS)} seed INSERT(s) -> {SQL_PATH.relative_to(ROOT)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync enterprise workflows to Live MySQL / init SQL")
    parser.add_argument("--dry-run", action="store_true", help="打印计划 UPSERT，不写库")
    parser.add_argument("--write-sql", action="store_true", help="重写 init SQL INSERT 段（version=1）")
    parser.add_argument("--host", default=MYSQL_DEFAULTS["host"])
    parser.add_argument("--port", type=int, default=MYSQL_DEFAULTS["port"])
    parser.add_argument("--user", default=MYSQL_DEFAULTS["user"])
    parser.add_argument("--password", default=MYSQL_DEFAULTS["password"])
    args = parser.parse_args()

    if args.write_sql:
        write_sql()
        return 0

    sync_live(
        dry_run=args.dry_run,
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
