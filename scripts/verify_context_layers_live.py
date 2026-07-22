#!/usr/bin/env python3
"""上下文 L1/L2/L3 Live 验收 — Admin API + 关键单测。

用法:
  python3 scripts/verify_context_layers_live.py
  python3 scripts/verify_context_layers_live.py --skip-unit
  python3 scripts/verify_context_layers_live.py --orchestrator-url http://127.0.0.1:8200

覆盖:
  U1 单测 — AssembledContext.forSubAgent 空记忆；L3 排除近窗；L2 冲突；Maintenance
  A1 Admin L2 — MySQL 种子 → list / update / void
  A2 Admin L1 — MySQL 种子 mid/far → GET 快照
  A3 Admin L3 — status + GC（maintenance runOnce）
  A4 Admin L3 reingest — 无消息会话返回 ingested=0（API 可达）

Orchestrator 不可达时 Admin 套件 soft-skip（exit 0，明确 SKIP 文案）；单测失败仍 FAIL。

环境变量:
  ORCHESTRATOR_URL（默认 http://127.0.0.1:8200）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from sunshine_lib import run_mysql, unwrap_r  # noqa: E402

ORCH = os.environ.get("ORCHESTRATOR_URL", "http://127.0.0.1:8200").rstrip("/")
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}

UNIT_TESTS = ",".join([
    "AssembledContextTest",
    "L3RecallServiceTest",
    "L2ConflictMergerTest",
    "ContextMaintenanceServiceTest",
    "L1CompressorTest",
])


@dataclass
class GateResult:
    gate: str
    status: str  # PASS | FAIL | SKIP
    detail: str = ""


@dataclass
class Report:
    results: list[GateResult] = field(default_factory=list)

    def add(self, gate: str, status: str, detail: str = "") -> None:
        self.results.append(GateResult(gate, status, detail))
        tag = {"PASS": "OK", "FAIL": "FAIL", "SKIP": "SKIP"}.get(status, status)
        print(f"[{tag}] {gate}: {detail}" if detail else f"[{tag}] {gate}")

    def failed(self) -> list[GateResult]:
        return [r for r in self.results if r.status == "FAIL"]


def host_port(url: str) -> tuple[str, int]:
    u = urlparse(url if "://" in url else f"http://{url}")
    return u.hostname or "127.0.0.1", u.port or 80


def url_reachable(url: str, *, timeout: float = 3.0) -> bool:
    try:
        requests.get(f"{url.rstrip('/')}/health", timeout=timeout)
        return True
    except requests.RequestException:
        pass
    try:
        host, port = host_port(url)
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def api_json(method: str, url: str, **kwargs: Any) -> dict:
    resp = requests.request(method, url, timeout=60, **kwargs)
    resp.raise_for_status()
    body = resp.json()
    if not isinstance(body, dict):
        raise RuntimeError(f"非 JSON 对象: {url}")
    return body


def run_unit_tests(report: Report) -> None:
    cmd = [
        "mvn", "test", "-pl", "orchestrator", "-am",
        f"-Dtest={UNIT_TESTS}",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-q",
    ]
    print(f"[UNIT] {' '.join(cmd)}")
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if proc.stdout:
        print(proc.stdout[-2000:] if len(proc.stdout) > 2000 else proc.stdout, end="")
    if proc.returncode != 0:
        if proc.stderr:
            print(proc.stderr[-1500:], file=sys.stderr)
        report.add("U1-unit", "FAIL", f"exit={proc.returncode}")
        return
    report.add(
        "U1-unit",
        "PASS",
        "AssembledContext.forSubAgent + L3 near-exclude + L2 conflict + maintenance",
    )


def mysql_exec(sql: str) -> None:
    run_mysql(sql, **MYSQL)


def ensure_schema() -> None:
    """幂等补列：已有库 CREATE IF NOT EXISTS 不会加 far_folded_msg_ids。"""
    mysql_exec("""
USE sunshine_chat;
SET @col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='sunshine_chat'
    AND TABLE_NAME='conversation_context_l1'
    AND COLUMN_NAME='far_folded_msg_ids'
);
SET @sql := IF(@col=0,
  'ALTER TABLE conversation_context_l1 ADD COLUMN far_folded_msg_ids MEDIUMTEXT NULL COMMENT ''JSON array of msgIds already folded into far_summary'' AFTER far_summary',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
""")


def seed_l2(user_id: str, state_id: str) -> None:
    sql = f"""
USE sunshine_chat;
DELETE FROM user_context_state WHERE id='{state_id}' OR (user_id='{user_id}' AND state_key='verify_pref_lang');
INSERT INTO user_context_state (
  id, user_id, tenant_id, kind, state_key, state_value, confidence, status,
  expires_at, source_msg_id, created_at, updated_at
) VALUES (
  '{state_id}', '{user_id}', 'default', 'preference', 'verify_pref_lang',
  'prefer-zh', 0.88, 'active',
  NULL, NULL, NOW(3), NOW(3)
);
"""
    mysql_exec(sql)


def seed_l1(conv_id: str, user_id: str) -> None:
    mid = json.dumps({"msg-mid-1": "中窗摘要：预算讨论"}, ensure_ascii=False)
    folded = json.dumps(["msg-far-1", "msg-far-2"], ensure_ascii=False)
    far = "远窗摘要：历史采购约定"
    # escape single quotes for SQL
    mid_sql = mid.replace("'", "''")
    folded_sql = folded.replace("'", "''")
    far_sql = far.replace("'", "''")
    sql = f"""
USE sunshine_chat;
DELETE FROM conversation_context_l1 WHERE conv_id='{conv_id}';
INSERT INTO conversation_context_l1 (
  conv_id, user_id, tenant_id, mid_answers, far_summary, far_folded_msg_ids,
  near_n, mid_n, updated_at
) VALUES (
  '{conv_id}', '{user_id}', 'default', '{mid_sql}', '{far_sql}', '{folded_sql}',
  8, 8, NOW(3)
);
"""
    mysql_exec(sql)


def cleanup_seed(user_id: str, state_id: str, conv_id: str) -> None:
    try:
        mysql_exec(f"""
USE sunshine_chat;
DELETE FROM user_context_state WHERE id='{state_id}' OR (user_id='{user_id}' AND state_key='verify_pref_lang');
DELETE FROM conversation_context_l1 WHERE conv_id='{conv_id}';
DELETE FROM chat_conversation WHERE id='{conv_id}';
""")
    except Exception as exc:  # noqa: BLE001
        print(f"[WARN] cleanup: {exc}", file=sys.stderr)


def check_admin_l2(orch: str, report: Report, user_id: str, state_id: str) -> None:
    try:
        seed_l2(user_id, state_id)
    except Exception as exc:  # noqa: BLE001
        report.add("A1-l2", "SKIP", f"MySQL 种子失败: {exc}")
        return
    try:
        body = api_json("GET", f"{orch}/api/admin/context/l2", params={"userId": user_id, "tenantId": "default"})
        rows = unwrap_r(body, context="list L2")
        if not isinstance(rows, list) or not any(r.get("id") == state_id for r in rows if isinstance(r, dict)):
            report.add("A1-l2", "FAIL", "list 未返回种子条目")
            return
        updated = unwrap_r(
            api_json(
                "PUT",
                f"{orch}/api/admin/context/l2/{state_id}",
                json={"stateValue": "prefer-zh-edited", "confidence": 0.91, "status": "active"},
            ),
            context="update L2",
        )
        if not isinstance(updated, dict) or updated.get("stateValue") != "prefer-zh-edited":
            report.add("A1-l2", "FAIL", f"update 未生效: {updated}")
            return
        voided = unwrap_r(
            api_json("POST", f"{orch}/api/admin/context/l2/{state_id}/void"),
            context="void L2",
        )
        if not isinstance(voided, dict) or voided.get("status") != "void":
            report.add("A1-l2", "FAIL", f"void 未生效: {voided}")
            return
        report.add("A1-l2", "PASS", "list/update/void")
    except Exception as exc:  # noqa: BLE001
        report.add("A1-l2", "FAIL", str(exc))


def check_admin_l1(orch: str, report: Report, user_id: str, conv_id: str) -> None:
    try:
        seed_l1(conv_id, user_id)
    except Exception as exc:  # noqa: BLE001
        report.add("A2-l1", "SKIP", f"MySQL 种子失败: {exc}")
        return
    try:
        data = unwrap_r(
            api_json("GET", f"{orch}/api/admin/context/l1", params={"convId": conv_id}),
            context="get L1",
        )
        if not isinstance(data, dict):
            report.add("A2-l1", "FAIL", "响应非对象")
            return
        mid = data.get("midAnswers") or {}
        far = data.get("farSummary") or ""
        if "msg-mid-1" not in mid or "远窗摘要" not in far:
            report.add("A2-l1", "FAIL", f"mid/far 快照不符: mid={mid} far={far!r}")
            return
        report.add("A2-l1", "PASS", "mid_answers + far_summary 可读")
    except Exception as exc:  # noqa: BLE001
        report.add("A2-l1", "FAIL", str(exc))


def check_admin_l3(orch: str, report: Report, user_id: str, conv_id: str) -> None:
    try:
        status = unwrap_r(
            api_json(
                "GET",
                f"{orch}/api/admin/context/l3/status",
                params={"userId": user_id, "tenantId": "default"},
            ),
            context="L3 status",
        )
        if not isinstance(status, dict) or "collection" not in status:
            report.add("A3-l3-status", "FAIL", f"status 字段缺失: {status}")
            return
        report.add(
            "A3-l3-status",
            "PASS",
            f"collection={status.get('collection')} note={status.get('note')} l1={status.get('l1RowCount')}",
        )
        gc = unwrap_r(api_json("POST", f"{orch}/api/admin/context/l3/gc"), context="L3 GC")
        if not isinstance(gc, dict) or not gc.get("ok"):
            report.add("A3-l3-gc", "FAIL", f"gc 失败: {gc}")
            return
        report.add("A3-l3-gc", "PASS", str(gc.get("message") or "ok"))
        # 空会话 reingest → ingested=0
        try:
            mysql_exec(f"""
USE sunshine_chat;
INSERT INTO chat_conversation (id, user_id, tenant_id, title, created_at, updated_at)
VALUES ('{conv_id}', '{user_id}', 'default', 'ctx-verify', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at=NOW(3);
""")
            data = unwrap_r(
                api_json("POST", f"{orch}/api/admin/context/l3/reingest", params={"convId": conv_id}),
                context="reingest",
            )
            if isinstance(data, dict) and "ingested" in data:
                report.add("A4-l3-reingest", "PASS", f"ingested={data.get('ingested')}")
            else:
                report.add("A4-l3-reingest", "FAIL", f"unexpected: {data}")
        except Exception as exc:  # noqa: BLE001
            report.add("A4-l3-reingest", "FAIL", str(exc))
    except Exception as exc:  # noqa: BLE001
        report.add("A3-l3-status", "FAIL", str(exc))


def main() -> int:
    parser = argparse.ArgumentParser(description="Context L1/L2/L3 Live 验收")
    parser.add_argument("--orchestrator-url", default=ORCH)
    parser.add_argument("--skip-unit", action="store_true")
    parser.add_argument("--mysql-host", default=MYSQL["host"])
    parser.add_argument("--mysql-port", type=int, default=MYSQL["port"])
    parser.add_argument("--mysql-user", default=MYSQL["user"])
    parser.add_argument("--mysql-password", default=MYSQL["password"])
    args = parser.parse_args()

    MYSQL["host"] = args.mysql_host
    MYSQL["port"] = args.mysql_port
    MYSQL["user"] = args.mysql_user
    MYSQL["password"] = args.mysql_password

    orch = args.orchestrator_url.rstrip("/")
    report = Report()

    print(f"=== Context Layers Live === Orchestrator={orch}")

    if not args.skip_unit:
        run_unit_tests(report)
    else:
        report.add("U1-unit", "SKIP", "--skip-unit")

    user_id = f"ctx-verify-{uuid.uuid4().hex[:8]}"
    state_id = uuid.uuid4().hex[:32]
    conv_id = uuid.uuid4().hex[:32]

    if not url_reachable(orch):
        msg = f"Orchestrator 不可达: {orch} — soft-skip Admin 套件（请 python3 scripts/start.py 或设 ORCHESTRATOR_URL）"
        print(f"[SKIP] admin-suite: {msg}")
        for gate in ("A1-l2", "A2-l1", "A3-l3-status", "A3-l3-gc", "A4-l3-reingest"):
            report.add(gate, "SKIP", "orchestrator down")
    else:
        try:
            ensure_schema()
        except Exception as exc:  # noqa: BLE001
            print(f"[WARN] ensure_schema: {exc}", file=sys.stderr)
        try:
            check_admin_l2(orch, report, user_id, state_id)
            check_admin_l1(orch, report, user_id, conv_id)
            check_admin_l3(orch, report, user_id, conv_id)
        finally:
            cleanup_seed(user_id, state_id, conv_id)

    failed = report.failed()
    print("---")
    for r in report.results:
        print(f"  {r.status:4} {r.gate}: {r.detail}")
    if failed:
        print(f"[FAIL] {len(failed)} gate(s) failed")
        return 1
    skipped = sum(1 for r in report.results if r.status == "SKIP")
    print(f"[PASS] context layers live ({skipped} skipped)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
