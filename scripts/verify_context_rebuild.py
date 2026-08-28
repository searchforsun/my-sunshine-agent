#!/usr/bin/env python3
"""账本重建校验（O4）— memory-ledger-view §6.2 / §8 验收 4。

只读对账：以 chat_message（账本）为源，经 Java 侧同源分区（L1Compressor）对账
conversation_context_l1 视图；对账算法完全在
GET /api/admin/context/l1/rebuild-check（复用 L1Compressor/TokenEstimator，
禁止脚本复制实现），本脚本只做驱动与汇总：

  扫描模式（默认）: 取最近 REBUILD_SCAN_LIMIT 个会话逐一核对；
      verdict=ERROR → exit 1；WARN（可解释漂移）仅打印。
  --conv-id: 单会话核对。
  --self-test: 构造夹具（45 轮账本 + 一致 L1 视图）→ 核对应 PASS
      → 删除 L1 行 → 核对应 ERROR（H1 账本可重建而视图缺失）
      → 恢复 → 核对应 PASS → 清理夹具。
      夹具写入仅涉本脚本自建的临时数据，退出即删除；核对本身只读。

用法:
  python3 scripts/verify_context_rebuild.py
  python3 scripts/verify_context_rebuild.py --conv-id <id>
  python3 scripts/verify_context_rebuild.py --self-test
环境变量:
  GATEWAY_URL（默认 http://127.0.0.1:8000）
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD（默认 127.0.0.1 / 3306 / root / root123）
  REBUILD_SCAN_LIMIT（默认 10）
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import uuid
from datetime import datetime

try:
    import requests
except ImportError:
    print("请先安装: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
MYSQL = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", "3306")),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root123"),
}
SCAN_LIMIT = int(os.environ.get("REBUILD_SCAN_LIMIT", "10"))

FIXTURE_ROUNDS = 45  # > turn-backstop(40) → shouldCompress=true，不依赖模型窗口


def fail(msg: str, *, hint: str | None = None) -> None:
    print(f"  ❌ FAIL: {msg}", file=sys.stderr)
    if hint:
        print(f"     → {hint}", file=sys.stderr)


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def warn(msg: str) -> None:
    print(f"  ⚠ {msg}")


def sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def mysql_exec(sql: str) -> None:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [mysql, "-h", MYSQL["host"], "-P", str(MYSQL["port"]),
         "-u", MYSQL["user"], f"-p{MYSQL['password']}",
         "sunshine_chat", "-N", "-B", "-e", sql],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")


def mysql_lines(sql: str) -> list[str]:
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client not found in PATH")
    proc = subprocess.run(
        [mysql, "-h", MYSQL["host"], "-P", str(MYSQL["port"]),
         "-u", MYSQL["user"], f"-p{MYSQL['password']}",
         "sunshine_chat", "-N", "-B", "-e", sql],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"MySQL failed: {proc.stderr or proc.stdout}")
    return [ln for ln in proc.stdout.splitlines() if ln.strip()]


def preflight_gateway() -> None:
    try:
        requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
    except requests.RequestException as exc:
        raise RuntimeError(
            f"Gateway 不可达: {GATEWAY_URL} ({exc}). 请先 python scripts/start.py"
        ) from exc


def auth_json(method: str, path: str, body: dict | None, token: str | None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def setup_token() -> str:
    user = f"o4rb_{datetime.now():%H%M%S%f}"
    password = "password123"
    reg = auth_json("POST", "/api/auth/register",
                    {"username": user, "password": password, "nickname": "O4Rebuild"}, None)
    if reg.get("code") != 200:
        raise RuntimeError(f"register failed: {reg}")
    login = auth_json("POST", "/api/auth/login", {"username": user, "password": password}, None)
    token = (login.get("data") or {}).get("token")
    if login.get("code") != 200 or not token:
        raise RuntimeError(f"login failed: {login}")
    return token


def rebuild_check(token: str, conv_id: str) -> dict:
    resp = auth_json("GET", f"/api/admin/context/l1/rebuild-check?convId={conv_id}", None, token)
    if resp.get("code") != 200:
        raise RuntimeError(f"rebuild-check failed conv={conv_id}: {resp}")
    data = resp.get("data")
    if not isinstance(data, dict):
        raise RuntimeError(f"rebuild-check 无 data conv={conv_id}: {resp}")
    return data


def recent_conv_ids(limit: int) -> list[str]:
    lines = mysql_lines(
        "SELECT id FROM chat_conversation ORDER BY updated_at DESC LIMIT " + str(max(1, limit)))
    return [ln.strip() for ln in lines if ln.strip()]


def print_view(view: dict) -> None:
    verdict = str(view.get("verdict") or "?")
    mode = str(view.get("mode") or "?")
    rate = view.get("summaryMatchRate")
    gap = view.get("gapRounds") or 0
    head = (f"[{verdict}] conv={view.get('convId')} kind={view.get('kind')} mode={mode} "
            f"ledgerMsgs={view.get('ledgerMessages')} rounds={view.get('ledgerRounds')} "
            f"folded={view.get('foldedCount')} midKeys={view.get('midKeys')} "
            + (f"gap={gap} " if gap else "")
            + f"rate={rate}")
    if verdict == "ERROR":
        fail(head)
        for e in view.get("errors") or []:
            print(f"     ERROR {e}", file=sys.stderr)
    else:
        print(f"  {head}")
    for w in view.get("warnings") or []:
        warn(f"WARN {w}")


def run_scan(token: str, conv_ids: list[str]) -> int:
    if not conv_ids:
        print("无可核对会话（chat_conversation 为空）")
        return 0
    views = []
    for cid in conv_ids:
        try:
            views.append(rebuild_check(token, cid))
        except Exception as exc:  # noqa: BLE001
            fail(f"rebuild-check 调用失败 conv={cid}: {exc}")
            return 1
    print(f"\n=== 逐会话对账（{len(views)} 个）===")
    for v in views:
        print_view(v)
    errors = [v for v in views if v.get("verdict") == "ERROR"]
    warns = [v for v in views if v.get("warnings")]
    print(f"\n--- 汇总: {len(views)} 会话 / ERROR {len(errors)} / 含 WARN {len(warns)} ---")
    if errors:
        print("❌ FAILED: 存在账本可重建但视图缺失/损坏的会话")
        return 1
    print("✅ ALL PASSED")
    return 0


def insert_fixture(conv_id: str, user_id: str, *, with_l1: bool) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    mysql_exec(
        "INSERT INTO chat_conversation (id, user_id, tenant_id, title, kind, created_at, updated_at) "
        f"VALUES ('{sql_escape(conv_id)}','{sql_escape(user_id)}','default','o4-fixture','chat',"
        f"'{now}','{now}')")
    values = []
    seq = 1
    for i in range(FIXTURE_ROUNDS):
        values.append(
            f"('u{i}','{sql_escape(conv_id)}',{seq},'user','问题{i}：核对夹具','completed',"
            f"0,'{now}','{now}')")
        values.append(
            f"('a{i}','{sql_escape(conv_id)}',{seq + 1},'assistant','回答{i}：核对夹具','completed',"
            f"0,'{now}','{now}')")
        seq += 2
    mysql_exec(
        "INSERT INTO chat_message (id, conversation_id, seq, role, content, status, "
        "resume_count, created_at, updated_at) VALUES " + ",".join(values))
    if with_l1:
        insert_fixture_l1(conv_id, user_id)


def insert_fixture_l1(conv_id: str, user_id: str) -> None:
    """滑动窗一致视图：near=8 mid=8 → far=r0..r28，折叠链与中窗摘要键均与分区一致。"""
    near_n, mid_n = 8, 8
    near_start = FIXTURE_ROUNDS - near_n          # 37
    mid_start = near_start - mid_n                # 29
    folded = []
    for i in range(mid_start):
        folded += [f"u{i}", f"a{i}"]
    mid_answers = {f"a{i}": f"摘要{i}" for i in range(mid_start, near_start)}
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    mysql_exec(
        "INSERT INTO conversation_context_l1 (conv_id, user_id, tenant_id, mid_answers, "
        "far_summary, far_folded_msg_ids, near_n, mid_n, updated_at) VALUES ("
        f"'{sql_escape(conv_id)}','{sql_escape(user_id)}','default',"
        f"'{sql_escape(json.dumps(mid_answers, ensure_ascii=False))}',"
        f"'远窗折叠摘要（o4 夹具）',"
        f"'{sql_escape(json.dumps(folded))}',"
        f"{near_n},{mid_n},'{now}')")


def cleanup_fixture(conv_id: str) -> None:
    cid = sql_escape(conv_id)
    deletes = [
        "DELETE FROM conversation_context_l1 WHERE conv_id='{0}'",
        "DELETE FROM chat_message WHERE conversation_id='{0}'",
        "DELETE FROM chat_conversation WHERE id='{0}'",
    ]
    for sql in deletes:
        try:
            mysql_exec(sql.format(cid))
        except RuntimeError as exc:
            warn(f"夹具清理失败: {exc}")


def expect(view: dict, verdict: str, *, error_prefix: str | None, label: str) -> bool:
    actual = str(view.get("verdict") or "?")
    if actual != verdict:
        fail(f"{label} 期望 verdict={verdict}，实际 {actual}；errors={view.get('errors')}")
        return False
    if error_prefix and not any(str(e).startswith(error_prefix)
                                for e in view.get("errors") or []):
        fail(f"{label} 缺少 {error_prefix} 错误；errors={view.get('errors')}")
        return False
    ok(f"{label} verdict={actual}"
       + (f"（含 {error_prefix}）" if error_prefix else "")
       + f" rate={view.get('summaryMatchRate')}")
    return True


def run_self_test(token: str) -> int:
    conv_id = f"o4fix{uuid.uuid4().hex[:12]}"
    user_id = "o4-script-user"
    print(f"\n=== self-test 夹具 conv={conv_id}（{FIXTURE_ROUNDS} 轮）===")
    passed = True
    try:
        insert_fixture(conv_id, user_id, with_l1=True)
        if not expect(rebuild_check(token, conv_id), "PASS",
                      error_prefix=None, label="正例（视图与账本一致）"):
            passed = False
        mysql_exec(f"DELETE FROM conversation_context_l1 WHERE conv_id='{sql_escape(conv_id)}'")
        if not expect(rebuild_check(token, conv_id), "ERROR",
                      error_prefix="H1", label="负例（删 L1 行）"):
            passed = False
        insert_fixture_l1(conv_id, user_id)
        if not expect(rebuild_check(token, conv_id), "PASS",
                      error_prefix=None, label="恢复（重插 L1 行）"):
            passed = False
    except Exception as exc:  # noqa: BLE001
        fail(f"self-test 执行异常: {exc}")
        passed = False
    finally:
        cleanup_fixture(conv_id)
    print("\n--- 汇总 ---")
    if passed:
        print("✅ self-test ALL PASSED")
        return 0
    print("❌ self-test FAILED")
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="O4 账本重建校验（只读）")
    parser.add_argument("--conv-id", help="单会话核对")
    parser.add_argument("--self-test", action="store_true", help="夹具正负例自检")
    parser.add_argument("--limit", type=int, default=SCAN_LIMIT, help="扫描会话数")
    args = parser.parse_args()

    print("=== 账本重建校验（O4）===")
    print(f"Gateway={GATEWAY_URL} MySQL={MYSQL['host']}:{MYSQL['port']}")
    try:
        preflight_gateway()
    except RuntimeError as exc:
        fail(str(exc))
        return 1
    try:
        token = setup_token()
    except Exception as exc:  # noqa: BLE001
        fail(f"登录失败: {exc}")
        return 1

    if args.self_test:
        return run_self_test(token)
    if args.conv_id:
        try:
            view = rebuild_check(token, args.conv_id)
        except Exception as exc:  # noqa: BLE001
            fail(f"rebuild-check 调用失败: {exc}")
            return 1
        print_view(view)
        return 1 if view.get("verdict") == "ERROR" else 0
    try:
        conv_ids = recent_conv_ids(args.limit)
    except Exception as exc:  # noqa: BLE001
        fail(f"扫描会话失败: {exc}")
        return 1
    return run_scan(token, conv_ids)


if __name__ == "__main__":
    raise SystemExit(main())
