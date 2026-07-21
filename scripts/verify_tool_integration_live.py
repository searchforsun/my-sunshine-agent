#!/usr/bin/env python3
"""4.8 工具集成 Live：SDK 发现 + invoke + MCP probe + 工具集 + HITL + 动态生效。

用法:
  python3 scripts/verify_tool_integration_live.py --suite all
  python3 scripts/verify_tool_integration_live.py --suite sdk
  python3 scripts/verify_tool_integration_live.py --suite mcp --start-missing

子套件:
  sdk     G1–G3  SDK 发现 / catalog / 解耦 / 可选 Chat SSE
  mcp     G4–G5  mcp.json 导入 + probe（无 npx 时 SKIP）
  toolset G6–G7  react-default 工具集 + disable 动态生效
  hitl    G8     approve_oa_task 写工具确认
  all     G1–G10

环境变量: GATEWAY_URL, TOOL_MANAGER_URL, TOOL_INTEGRATION_TIMEOUT_SEC, TOOL_INTEGRATION_SKIP_CHAT
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
TM_URL = os.environ.get("TOOL_MANAGER_URL", "http://127.0.0.1:8210").rstrip("/")
TIMEOUT_SEC = int(os.environ.get("TOOL_INTEGRATION_TIMEOUT_SEC", "180"))
SKIP_CHAT = os.environ.get("TOOL_INTEGRATION_SKIP_CHAT", "").lower() in ("1", "true", "yes")

SDK_APPS = ("sunshine-finance", "sunshine-oa")
SDK_TOOL_IDS = (
    "sdk__sunshine-finance__list_my_expenses",
    "sdk__sunshine-finance__get_expense_detail",
    "sdk__sunshine-finance__summarize_my_expenses",
    "sdk__sunshine-oa__list_oa_tasks",
    "sdk__sunshine-oa__approve_oa_task",
)
FIN_LIST = SDK_TOOL_IDS[0]
OA_LIST = SDK_TOOL_IDS[3]
OA_APPROVE = SDK_TOOL_IDS[4]
MCP_DEMO_ID = "demo-fs"
MCP_DEMO_JSON = json.dumps(
    {
        "mcpServers": {
            MCP_DEMO_ID: {
                "command": "npx",
                "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
            }
        }
    },
    ensure_ascii=False,
)
SPECIAL_TOOL_IDS = ("manage_tasks", "search_knowledge")


def wait_port(port: int, timeout: float = 90.0) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2)
            s.close()
            return True
        except OSError:
            time.sleep(2)
    return False


def start_missing_services() -> None:
    from sunshine_lib import start_java_detached

    services = [
        ("finance", "finance-service", "sunshine-finance", 8710),
        ("oa", "oa-service", "sunshine-oa", 8700),
        ("tool-manager", "tool-manager", "sunshine-tool-manager", 8210),
        ("orchestrator", "orchestrator", "sunshine-orchestrator", 8200),
        ("bff", "bff", "sunshine-bff", 8001),
        ("gateway", "gateway", "sunshine-gateway", 8000),
        ("auth", "auth-center", "sunshine-auth", 8100),
    ]
    for name, module, artifact, port in services:
        if wait_port(port, timeout=2):
            print(f"[SKIP] {name} :{port} already up")
            continue
        print(f"[START] {name} :{port}")
        start_java_detached(module, artifact, service_name=name, wait_sec=8)
    for name, _, _, port in services:
        if not wait_port(port):
            raise RuntimeError(f"service not ready: {name} :{port}")


def services_ready(required_ports: tuple[int, ...]) -> bool:
    return all(wait_port(p, timeout=3) for p in required_ports)


def auth_headers() -> dict[str, str]:
    user = f"toolint_{uuid.uuid4().hex[:10]}"
    password = "password123"
    reg = requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": user, "password": password, "nickname": "tool-int"},
        timeout=30,
    )
    reg.raise_for_status()
    if reg.json().get("code") != 200:
        raise RuntimeError(f"register failed: {reg.json()}")
    login = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": user, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.json()}")
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def unwrap_r(body: dict, *, context: str = "request") -> dict | list | None:
    from sunshine_lib import unwrap_r as _unwrap

    return _unwrap(body, context=context)


def admin_json(method: str, path: str, headers: dict, **kwargs) -> dict | list | None:
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, timeout=60, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def catalog_entries(*, enabled_only: bool, headers: dict | None = None) -> list[dict]:
    params = {"enabledOnly": "true"} if enabled_only else {}
    if headers:
        resp = requests.get(
            f"{GATEWAY_URL}/api/tools/catalog",
            headers=headers,
            params=params,
            timeout=30,
        )
    else:
        resp = requests.get(f"{TM_URL}/api/tools/catalog", params=params, timeout=30)
    resp.raise_for_status()
    data = unwrap_r(resp.json(), context="catalog")
    return data if isinstance(data, list) else []


def sync_sdk_app(app_id: str, headers: dict) -> None:
    admin_json("POST", f"/api/admin/tools/sdk-applications/{app_id}/sync", headers)
    print(f"[OK] sync sdk app {app_id}")


def patch_tool(tool_id: str, body: dict, headers: dict) -> None:
    admin_json("PATCH", f"/api/admin/tools/{tool_id}", headers, json=body)
    print(f"[OK] patch tool {tool_id} {body}")


def enable_sdk_tools(headers: dict) -> None:
    for tool_id in SDK_TOOL_IDS:
        patch_tool(tool_id, {"enabled": True}, headers)


class SseCollector:
    def __init__(self) -> None:
        self.confirmation: dict | None = None
        self.steps: list[dict] = []
        self.content_chunks: list[str] = []
        self.error: Exception | None = None
        self._done = threading.Event()

    def wait_done(self, timeout: float) -> None:
        if not self._done.wait(timeout):
            raise TimeoutError("SSE 未在超时内结束")

    def parse_line(self, line: str) -> None:
        if not line.startswith("data:"):
            return
        payload = line[5:].strip()
        if not payload:
            return
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            return
        t = obj.get("type")
        if t == "confirmation":
            self.confirmation = obj
        elif t == "step":
            self.steps.append(obj)
        elif t == "content" and obj.get("text"):
            self.content_chunks.append(obj["text"])


def chat_sse(
    headers: dict,
    conv_id: str,
    query: str,
    *,
    approved: bool | None = None,
) -> SseCollector:
    collector = SseCollector()
    confirm_called = threading.Event()

    def run_sse() -> None:
        try:
            body = {
                "content": query,
                "conversationId": conv_id,
                "executionPreference": "react",
            }
            with requests.post(
                f"{GATEWAY_URL}/api/chat/stream",
                headers=headers,
                json=body,
                stream=True,
                timeout=(10, TIMEOUT_SEC),
            ) as resp:
                resp.raise_for_status()
                for raw in resp.iter_lines(decode_unicode=True):
                    if raw is None:
                        continue
                    line = raw.strip()
                    if not line.startswith("data:"):
                        continue
                    collector.parse_line(line)
                    if collector.confirmation and approved is not None and not confirm_called.is_set():
                        confirm_called.set()
                        token_val = collector.confirmation.get("confirmationToken")
                        r = requests.post(
                            f"{GATEWAY_URL}/api/chat/confirm-tool",
                            headers=headers,
                            json={"token": token_val, "approved": approved},
                            timeout=30,
                        )
                        r.raise_for_status()
                        if not r.json().get("accepted"):
                            raise AssertionError(f"confirm-tool rejected: {r.json()}")
        except Exception as e:
            collector.error = e
        finally:
            collector._done.set()

    threading.Thread(target=run_sse, daemon=True).start()
    collector.wait_done(TIMEOUT_SEC + 30)
    if collector.error:
        raise collector.error
    return collector


def create_conversation(headers: dict) -> str:
    body = requests.post(
        f"{GATEWAY_URL}/api/conversations",
        headers=headers,
        json={},
        timeout=30,
    ).json()
    conv_id = (body.get("data") or body).get("id")
    if not conv_id:
        raise RuntimeError(f"create conversation failed: {body}")
    return conv_id


def find_tool_steps(steps: list[dict], tool_id: str) -> list[dict]:
    prefix = f"tool-{tool_id}"
    return [s for s in steps if str(s.get("id", "")).startswith(prefix)]


def run_g1_sdk_discovery(headers: dict) -> dict:
    print("\n[G1] SDK discovery")
    for app_id in SDK_APPS:
        sync_sdk_app(app_id, headers)
    apps = admin_json("GET", "/api/admin/tools/sdk-applications", headers)
    if not isinstance(apps, list):
        raise AssertionError("sdk-applications 响应应为列表")
    app_ids = {a.get("id") for a in apps}
    missing_apps = [a for a in SDK_APPS if a not in app_ids]
    if missing_apps:
        raise AssertionError(f"缺少 SDK 应用: {missing_apps}")
    online = [a.get("id") for a in apps if a.get("status") == "online" and a.get("id") in SDK_APPS]
    print(f"  apps={sorted(app_ids)} online={online}")
    all_catalog = catalog_entries(enabled_only=False)
    discovered = {e.get("id") for e in all_catalog if e.get("id") in SDK_TOOL_IDS}
    if len(discovered) < len(SDK_TOOL_IDS):
        raise AssertionError(f"Catalog 缺少 SDK 工具: 期望 {SDK_TOOL_IDS}, 实际 {sorted(discovered)}")
    print(f"[OK] G1: {len(SDK_APPS)} SDK apps, {len(discovered)} tools in catalog")
    return {"pass": True, "online_apps": online, "tool_count": len(discovered)}


def run_g2_sdk_invoke_chat(headers: dict) -> dict:
    print("\n[G2] SDK invoke via ReAct Chat")
    if SKIP_CHAT:
        print("[SKIP] G2 chat SSE (TOOL_INTEGRATION_SKIP_CHAT=1)")
        return {"pass": True, "skipped": True}
    conv_id = create_conversation(headers)
    query = (
        f"请仅调用 {FIN_LIST} 工具，参数 status=pending，"
        "查询待审批财务消息数量，不要调用其它工具。"
    )
    collector = chat_sse(headers, conv_id, query)
    tool_steps = find_tool_steps(collector.steps, FIN_LIST)
    done = [s for s in tool_steps if s.get("lifecycle") in ("done",) or s.get("status") == "done"]
    if not done:
        raise AssertionError(
            f"未见 {FIN_LIST} 完成步骤: {json.dumps(tool_steps, ensure_ascii=False)[:400]}"
        )
    print(f"[OK] G2: ReAct 调 {FIN_LIST} 步骤 done")
    return {"pass": True, "tool_steps": len(tool_steps)}


def run_g3_decouple() -> dict:
    print("\n[G3] tool-manager 解耦（无 finance/oa HTTP client）")
    tm_java = ROOT / "tool-manager" / "src" / "main" / "java"
    forbidden_names = (
        "FinanceServiceClient",
        "OaServiceClient",
        "FinanceToolHandler",
        "OaToolHandler",
        "ApproveOaTaskToolHandler",
    )
    hits: list[str] = []
    for path in tm_java.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for name in forbidden_names:
            if name in text:
                hits.append(f"{path.name}:{name}")
    if hits:
        raise AssertionError(f"tool-manager 仍含旧桥接: {hits[:5]}")
    cmd = ["mvn", "-pl", "tool-manager", "-am", "compile", "-q"]
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout)[-800:]
        raise RuntimeError(f"tool-manager compile failed: {tail}")
    print("[OK] G3: 无旧 Handler/Client，compile PASS")
    return {"pass": True}


def run_g4_g5_mcp(headers: dict) -> dict:
    print("\n[G4/G5] MCP import + probe")
    if not shutil.which("npx"):
        print("[SKIP] G4/G5: npx 不可用，跳过 MCP 套件")
        return {"pass": True, "skipped": True, "reason": "npx unavailable"}
    admin_json(
        "POST",
        "/api/admin/mcp/servers/import",
        headers,
        data=MCP_DEMO_JSON.encode("utf-8"),
    )
    print(f"[OK] import mcp.json server={MCP_DEMO_ID}")
    admin_json("POST", f"/api/admin/mcp/servers/{MCP_DEMO_ID}/probe", headers)
    servers = admin_json("GET", "/api/admin/mcp/servers", headers)
    server = next((s for s in servers if s.get("id") == MCP_DEMO_ID), None) if isinstance(servers, list) else None
    if not server:
        raise AssertionError(f"未找到 MCP server {MCP_DEMO_ID}")
    if server.get("probeStatus") != "ok":
        raise AssertionError(f"probe 失败: status={server.get('probeStatus')} err={server.get('probeError')}")
    all_catalog = catalog_entries(enabled_only=False)
    mcp_tools = [e for e in all_catalog if str(e.get("id", "")).startswith(f"mcp__{MCP_DEMO_ID}__")]
    if not mcp_tools:
        raise AssertionError(f"probe 后 Catalog 无 mcp__{MCP_DEMO_ID}__* 工具")
    first_hashes = {t.get("id"): json.dumps(t.get("parameters"), sort_keys=True) for t in mcp_tools}
    print(f"[OK] G4: probe ok, mcp tools={len(mcp_tools)}")
    time.sleep(1)
    admin_json("POST", f"/api/admin/mcp/servers/{MCP_DEMO_ID}/probe", headers)
    all_catalog_2 = catalog_entries(enabled_only=False)
    mcp_tools_2 = [e for e in all_catalog_2 if str(e.get("id", "")).startswith(f"mcp__{MCP_DEMO_ID}__")]
    second_hashes = {t.get("id"): json.dumps(t.get("parameters"), sort_keys=True) for t in mcp_tools_2}
    if not second_hashes:
        raise AssertionError("G5: 二次 probe 后 mcp 工具消失")
    print("[OK] G5: MCP refresh 后 schema 仍可用")
    return {"pass": True, "mcp_tool_count": len(mcp_tools), "schema_stable": first_hashes == second_hashes}


def run_g6_g7_toolset(headers: dict) -> dict:
    print("\n[G6] react-default 工具集 members API")
    subset = [FIN_LIST, OA_LIST]
    add_resp = admin_json(
        "POST",
        "/api/admin/tools/sets/react-default/members:add",
        headers,
        json={"items": [{"toolId": tid} for tid in subset]},
    )
    added = (add_resp or {}).get("added") if isinstance(add_resp, dict) else None
    skipped = (add_resp or {}).get("skipped") if isinstance(add_resp, dict) else None
    added_set = set(added or [])
    skipped_set = set(skipped or [])
    if added_set | skipped_set != set(subset):
        raise AssertionError(
            f"members:add 期望 added+skipped 覆盖 {subset}, 实际 added={added}, skipped={skipped}"
        )
    page_resp = admin_json(
        "GET",
        "/api/admin/tools/sets/react-default/members?page=1&size=50",
        headers,
    )
    items = (page_resp or {}).get("items") if isinstance(page_resp, dict) else []
    page_ids = {i.get("toolId") for i in items if isinstance(i, dict)}
    missing = [tid for tid in subset if tid not in page_ids]
    if missing:
        raise AssertionError(f"members 列表缺少 {missing}, 实际 toolIds={sorted(page_ids)[:10]}...")
    print(f"[OK] G6: react-default 含 {subset}（共 {len(page_ids)} 成员）")

    print("\n[G7] disable 工具动态生效（成员保留、运行时排除）")
    enable_sdk_tools(headers)
    before = {e.get("id") for e in catalog_entries(enabled_only=True, headers=headers)}
    if FIN_LIST not in before:
        raise AssertionError(f"enable 后 catalog 缺少 {FIN_LIST}")
    patch_tool(FIN_LIST, {"enabled": False}, headers)
    time.sleep(2)
    after = {e.get("id") for e in catalog_entries(enabled_only=True, headers=headers)}
    if FIN_LIST in after:
        raise AssertionError(f"disable 后 enabledOnly catalog 仍含 {FIN_LIST}")
    page_after = admin_json(
        "GET",
        "/api/admin/tools/sets/react-default/members?page=1&size=50",
        headers,
    )
    items = (page_after or {}).get("items") if isinstance(page_after, dict) else []
    fin_row = next((i for i in items if i.get("toolId") == FIN_LIST), None)
    if not fin_row:
        raise AssertionError(f"disable 后 react-default 成员仍应保留 {FIN_LIST}")
    patch_tool(FIN_LIST, {"enabled": True}, headers)
    print("[OK] G7: disable 后 enabledOnly catalog 已排除，成员仍保留")
    return {"pass": True, "react_default": subset}


def run_g8_hitl(headers: dict) -> dict:
    print(f"\n[G8] HITL {OA_APPROVE}")
    entries = catalog_entries(enabled_only=False)
    hit = next((e for e in entries if e.get("id") == OA_APPROVE), None)
    if not hit:
        raise AssertionError(f"catalog 缺少 {OA_APPROVE}")
    if hit.get("sideEffect") != "write":
        raise AssertionError(f"{OA_APPROVE} sideEffect={hit.get('sideEffect')!r}")
    patch_tool(OA_APPROVE, {"enabled": True}, headers)
    query = f"请调用 {OA_APPROVE} 工具审批 OA 待办 taskId=T1001，不要查询其它工具。"
    conv_ok = create_conversation(headers)
    ok = chat_sse(headers, conv_ok, query, approved=True)
    if not ok.confirmation:
        raise AssertionError("未收到 SSE type:confirmation")
    tool_steps = find_tool_steps(ok.steps, OA_APPROVE)
    done = [s for s in tool_steps if s.get("lifecycle") == "done" or s.get("status") == "done"]
    if not done:
        raise AssertionError(f"确认后工具步骤未完成: {json.dumps(tool_steps, ensure_ascii=False)[:400]}")
    print(f"[OK] G8: {OA_APPROVE} confirmation + done")
    return {"pass": True}


def run_g9_compat(headers: dict) -> dict:
    print("\n[G9] 工具 ID 向后兼容")
    entries = catalog_entries(enabled_only=False)
    ids = {e.get("id") for e in entries}
    missing = [t for t in SDK_TOOL_IDS if t not in ids]
    if missing:
        raise AssertionError(f"workflow/skill 兼容 ID 缺失: {missing}")
    print(f"[OK] G9: 5 个 SDK 工具 ID 均在 catalog")
    return {"pass": True}


def run_g10_special_tools(headers: dict) -> dict:
    print("\n[G10] 特殊工具不进 DB Catalog")
    entries = catalog_entries(enabled_only=False)
    ids = {e.get("id") for e in entries}
    leaked = [t for t in SPECIAL_TOOL_IDS if t in ids]
    if leaked:
        raise AssertionError(f"特殊工具不应出现在 tool-manager catalog: {leaked}")
    print(f"[OK] G10: {SPECIAL_TOOL_IDS} 不在 DB catalog")
    return {"pass": True}


def ensure_sdk_catalog(headers: dict) -> None:
    """同步 SDK 应用并启用 5 个 Demo 工具（hitl/toolset 套件前置）。"""
    for app_id in SDK_APPS:
        sync_sdk_app(app_id, headers)
    enable_sdk_tools(headers)


def run_suite(name: str, headers: dict) -> dict[str, dict]:
    report: dict[str, dict] = {}
    if name in ("sdk", "all"):
        report["G1"] = run_g1_sdk_discovery(headers)
        enable_sdk_tools(headers)
        enabled = catalog_entries(enabled_only=True, headers=headers)
        enabled_ids = {e.get("id") for e in enabled}
        expected = set(SDK_TOOL_IDS)
        if enabled_ids >= expected:
            print(f"[OK] enabledOnly catalog 含 {len(expected)} SDK 工具")
        else:
            missing = expected - enabled_ids
            raise AssertionError(f"enabledOnly catalog 缺少: {sorted(missing)}")
        report["G2"] = run_g2_sdk_invoke_chat(headers)
        report["G3"] = run_g3_decouple()
    if name in ("mcp", "all"):
        report["G4/G5"] = run_g4_g5_mcp(headers)
    if name in ("toolset", "all"):
        if name == "toolset":
            ensure_sdk_catalog(headers)
        report["G6/G7"] = run_g6_g7_toolset(headers)
    if name in ("hitl", "all"):
        if name == "hitl":
            ensure_sdk_catalog(headers)
        report["G8"] = run_g8_hitl(headers)
    if name == "all":
        report["G9"] = run_g9_compat(headers)
        report["G10"] = run_g10_special_tools(headers)
    return report


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="4.8 工具集成 Live 验收")
    p.add_argument(
        "--suite",
        choices=["sdk", "mcp", "toolset", "hitl", "all"],
        default="all",
        help="验收子套件",
    )
    p.add_argument("--gateway", default=GATEWAY_URL, help="Gateway 基址")
    p.add_argument("--tool-manager", default=TM_URL, help="tool-manager 基址（catalog 直连）")
    p.add_argument("--start-missing", action="store_true", help="启动缺失的核心服务")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    global GATEWAY_URL, TM_URL
    GATEWAY_URL = args.gateway.rstrip("/")
    TM_URL = args.tool_manager.rstrip("/")

    print(f"=== Tool Integration Live 4.8 ===\nsuite={args.suite} gateway={GATEWAY_URL}")

    if args.start_missing:
        start_missing_services()

    required = (8000, 8001, 8200, 8210, 8710, 8700) if args.suite in ("sdk", "hitl", "toolset", "all") else (8000, 8001, 8210)
    if args.suite in ("mcp", "all"):
        required = tuple(set(required + (8000, 8001, 8210)))
    if not services_ready(required):
        missing = [p for p in required if not wait_port(p, timeout=2)]
        print(f"[FAIL] 端口未就绪: {missing}；请先启动服务或加 --start-missing", file=sys.stderr)
        return 1

    headers = auth_headers()
    try:
        report = run_suite(args.suite, headers)
    except (AssertionError, RuntimeError, TimeoutError) as exc:
        print(f"\n[FAIL] {exc}", file=sys.stderr)
        return 1

    all_pass = all(v.get("pass") for v in report.values())
    print(f"\n=== Report ===\n{json.dumps(report, ensure_ascii=False, indent=2)}")
    if all_pass:
        print("[PASS] Tool Integration Live 4.8")
        return 0
    print("[FAIL] Tool Integration Live 4.8")
    return 1


if __name__ == "__main__":
    sys.exit(main())
