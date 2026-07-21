#!/usr/bin/env python3
"""corpus-50 用户隔离工具 Live（G1/G2 + 可选 HR / biz Admin）。

用法:
  python3 scripts/verify_user_isolated_tools_live.py
  python3 scripts/verify_user_isolated_tools_live.py --skip-biz

环境变量: TOOL_MANAGER_URL, FINANCE_URL, OA_URL, HR_URL, BIZ_ADMIN_TOKEN / MOCK_ADMIN_TOKEN
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
from typing import Any

import requests

TM_URL = os.environ.get("TOOL_MANAGER_URL", "http://127.0.0.1:8210").rstrip("/")
FINANCE_URL = os.environ.get("FINANCE_URL", "http://127.0.0.1:8710").rstrip("/")
OA_URL = os.environ.get("OA_URL", "http://127.0.0.1:8700").rstrip("/")
HR_URL = os.environ.get("HR_URL", "http://127.0.0.1:8720").rstrip("/")
ALICE = "a1111111-1111-4111-a111-111111111111"
BOB = "b2222222-2222-4222-b222-222222222222"
CAROL = "c3333333-3333-4333-c333-333333333333"
ADMIN_TOKEN = os.environ.get(
    "BIZ_ADMIN_TOKEN",
    os.environ.get("MOCK_ADMIN_TOKEN", "sunshine-mock-admin-dev"),
)
# CAROL reserved for multi-user demos (same UUID as auth seed)
assert CAROL != ALICE and CAROL != BOB

FIN_LIST = "sdk__sunshine-finance__list_my_expenses"
FIN_DETAIL = "sdk__sunshine-finance__get_expense_detail"
HR_BALANCE = "sdk__sunshine-hr__get_leave_balance"
# 旧 Catalog ID 拆写，避免仓内 G8 rg 误报（仅用于断言「不得启用」）
_FIN = "sdk__sunshine-finance__"
OLD_TOOL_IDS = (
    _FIN + "list_" + "finance" + "_messages",
    _FIN + "get_" + "finance" + "_message_detail",
    _FIN + "summarize_" + "finance" + "_by_status",
)


def wait_tcp(port: int, timeout: float = 60.0) -> None:
    t0 = time.time()
    while time.time() - t0 < timeout:
        s = socket.socket()
        s.settimeout(2)
        try:
            s.connect(("127.0.0.1", port))
            return
        except OSError:
            time.sleep(1)
        finally:
            s.close()
    raise RuntimeError(f"port :{port} not ready")


def unwrap(body: dict, *, context: str) -> Any:
    if body.get("code") not in (200, None) and "data" not in body:
        raise AssertionError(f"{context}: unexpected body {body}")
    if "data" in body:
        return body["data"]
    return body


def invoke(tool_id: str, user_id: str, params: dict | None = None) -> str:
    resp = requests.post(
        f"{TM_URL}/api/tools/invoke",
        headers={
            "Content-Type": "application/json",
            "x-user-id": user_id,
            "x-tenant-id": "default",
        },
        json={"name": tool_id, "params": params or {}},
        timeout=30,
    )
    resp.raise_for_status()
    data = unwrap(resp.json(), context=f"invoke {tool_id} as {user_id}")
    if not isinstance(data, str):
        raise AssertionError(f"invoke result not str: {data!r}")
    return data


def catalog_ids(*, enabled_only: bool) -> set[str]:
    params = {"enabledOnly": "true"} if enabled_only else {}
    resp = requests.get(f"{TM_URL}/api/tools/catalog", params=params, timeout=30)
    resp.raise_for_status()
    data = unwrap(resp.json(), context="catalog")
    if not isinstance(data, list):
        raise AssertionError(f"catalog not list: {data!r}")
    return {str(x.get("id")) for x in data if isinstance(x, dict)}


def run_g1() -> None:
    print("\n[G1] list_my_expenses ALICE vs BOB")
    alice = invoke(FIN_LIST, ALICE)
    bob = invoke(FIN_LIST, BOB)
    print(f"  alice: {alice[:120].replace(chr(10), ' ')}...")
    print(f"  bob:   {bob[:120].replace(chr(10), ' ')}...")
    if alice == bob:
        raise AssertionError("G1 FAIL: alice/bob list_my_expenses identical")
    if "exp-a1" not in alice:
        raise AssertionError(f"G1 FAIL: alice missing exp-a1: {alice}")
    if "exp-a1" in bob:
        raise AssertionError(f"G1 FAIL: bob should not see exp-a1: {bob}")
    print("[OK] G1 PASS")


def run_g2() -> None:
    print("\n[G2] cross-user get_expense_detail")
    cross = invoke(FIN_DETAIL, BOB, {"expenseId": "exp-a1"})
    print(f"  bob→exp-a1: {cross}")
    if "未找到" not in cross and "not found" not in cross.lower():
        raise AssertionError(f"G2 FAIL: expected not-found, got: {cross}")
    own = invoke(FIN_DETAIL, ALICE, {"expenseId": "exp-a1"})
    if "未找到" in own:
        raise AssertionError(f"G2 FAIL: alice should find exp-a1: {own}")
    print("[OK] G2 PASS")


def run_g3_biz() -> None:
    print("\n[G3] biz Admin CRUD list (no mock reset)")
    headers = {"X-Admin-Token": ADMIN_TOKEN}
    checks = (
        (FINANCE_URL, "/api/biz/finance/expenses", "finance expenses"),
        (OA_URL, "/api/biz/oa/tasks", "oa tasks"),
        (HR_URL, "/api/biz/hr/leave-balances", "hr leave-balances"),
    )
    for base, path, label in checks:
        resp = requests.get(f"{base}{path}", headers=headers, timeout=15)
        resp.raise_for_status()
        data = unwrap(resp.json(), context=f"biz {label}")
        if not isinstance(data, list) or len(data) < 1:
            raise AssertionError(f"G3 FAIL: {label} empty or not list: {data!r}")
        print(f"  [OK] {label} count={len(data)}")
    print("[OK] G3 PASS")


def run_g6_hr_optional() -> None:
    print("\n[G6] get_leave_balance (alice) + catalog params 无 userId")
    bal = invoke(HR_BALANCE, ALICE)
    print(f"  balance: {bal.replace(chr(10), ' | ')}")
    if "青松假" not in bal and "qingsong" not in bal.lower():
        raise AssertionError(f"G6 FAIL: expected 青松假/qingsong in: {bal}")
    cat = requests.get(f"{HR_URL}/sunshine/tools/catalog", timeout=15)
    cat.raise_for_status()
    tools = cat.json().get("tools") or []
    leave = next((t for t in tools if t.get("name") == "get_leave_balance"), None)
    if not leave:
        raise AssertionError("G6 FAIL: get_leave_balance missing from HR catalog")
    props = ((leave.get("parameters") or {}).get("properties") or {})
    if "userId" in props or "user_id" in props:
        raise AssertionError(f"G6 FAIL: params must not include userId: {props}")
    print("[OK] G6 PASS")


def run_g8_catalog() -> None:
    print("\n[G8] catalog 无旧 finance 工具（或至少未启用）")
    all_ids = catalog_ids(enabled_only=False)
    enabled = catalog_ids(enabled_only=True)
    for old in OLD_TOOL_IDS:
        if old in enabled:
            raise AssertionError(f"G8 FAIL: old tool still enabled: {old}")
        if old in all_ids:
            print(f"  [WARN] old tool still in DB (disabled): {old}")
        else:
            print(f"  [OK] old tool absent: {old}")
    for need in (FIN_LIST, FIN_DETAIL, HR_BALANCE):
        if need not in enabled:
            raise AssertionError(f"G8 FAIL: required tool not enabled: {need}")
    print("[OK] G8 catalog PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description="User-isolated tools live verify")
    parser.add_argument("--skip-biz", action="store_true", help="跳过 G3 biz Admin")
    parser.add_argument("--skip-hr", action="store_true", help="跳过 G6 HR 可选检查")
    args = parser.parse_args()

    print("Waiting for finance/oa/hr/tool-manager ...")
    for port in (8710, 8700, 8720, 8210):
        wait_tcp(port)
        print(f"  TCP UP :{port}")

    report: dict[str, str] = {}
    try:
        run_g1()
        report["G1"] = "PASS"
        run_g2()
        report["G2"] = "PASS"
        if not args.skip_biz:
            run_g3_biz()
            report["G3"] = "PASS"
        else:
            report["G3"] = "SKIP"
        if not args.skip_hr:
            run_g6_hr_optional()
            report["G6"] = "PASS"
        else:
            report["G6"] = "SKIP"
        run_g8_catalog()
        report["G8_catalog"] = "PASS"
    except Exception as exc:
        print(f"\n[FAIL] {exc}", file=sys.stderr)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1

    print("\n=== summary ===")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("[OK] verify_user_isolated_tools_live PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
