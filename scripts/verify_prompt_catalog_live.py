#!/usr/bin/env python3
"""4.11 Prompt Catalog Live 验收 — Catalog / dry-run / priority / rollback / 热更新 / golden-set。

用法:
  python3 scripts/verify_prompt_catalog_live.py
  PROMPT_MANAGER_URL=http://127.0.0.1:8500 python3 scripts/verify_prompt_catalog_live.py

环境变量:
  PROMPT_MANAGER_URL   默认 http://127.0.0.1:8500（直连 prompt-manager，免 BFF 鉴权）
  ORCHESTRATOR_URL     默认 http://127.0.0.1:8200（P5：catalogVersion 热更新硬门）
  PROMPT_CATALOG_REFRESH_MS  默认 5000（与 Nacos prompt-catalog.refresh-ms 对齐）

检查门:
  P1  GET /api/prompts/catalog — catalogVersion>=1；含 routing-rule 与 system-prompt|mode-overlay.react
  P2  POST /api/prompts/routing/dry-run — structural 样例命中
  P3  调高 regex priority → dry-run 命中必须变化；再恢复 priority
  P4  publish + rollback
  P5  orchestrator GET /api/prompt-catalog/version 追上 prompt-manager catalogVersion（硬门）
  P6  golden-set 子集 dry-run（structural / finance-list / peer / react-policy）
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from typing import Any

import requests

from sunshine_lib import unwrap_r

DEFAULT_PROMPT_URL = os.environ.get("PROMPT_MANAGER_URL", "http://127.0.0.1:8500").rstrip("/")
DEFAULT_ORCH_URL = os.environ.get("ORCHESTRATOR_URL", "http://127.0.0.1:8200").rstrip("/")
REFRESH_MS = int(os.environ.get("PROMPT_CATALOG_REFRESH_MS", "5000"))

STRUCTURAL_QUERY = "先检索制度再分析报销合规"
OVERLAP_QUERY = "先检索制度再列出待审批报销并对合规分析"
FINANCE_LIST_ID = "routing-rule.rule-finance-list-pending"
FINANCE_LIST_QUERY = "列出待审批的差旅报销"
PEER_QUERY = "请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证"
REACT_POLICY_QUERY = "差旅办法里怎么规定"
TIMEOUT = 20

_BASE = DEFAULT_PROMPT_URL
_ORCH = DEFAULT_ORCH_URL


def api_json(method: str, path: str, **kwargs: Any) -> Any:
    url = f"{_BASE}{path}"
    resp = requests.request(method, url, timeout=TIMEOUT, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def service_ready(base: str, path: str = "/api/prompts/catalog") -> bool:
    try:
        r = requests.get(f"{base}{path}", timeout=5)
        return r.status_code < 500
    except requests.RequestException:
        return False


def catalog_version() -> int:
    data = api_json("GET", "/api/prompts/catalog")
    return int(data.get("catalogVersion") or 0)


def orch_catalog_version() -> int:
    r = requests.get(f"{_ORCH}/api/prompt-catalog/version", timeout=5)
    r.raise_for_status()
    body = r.json()
    data = body.get("data", body) if isinstance(body, dict) else body
    if isinstance(data, dict) and "catalogVersion" in data:
        return int(data["catalogVersion"])
    if isinstance(body, dict) and "catalogVersion" in body:
        return int(body["catalogVersion"])
    raise RuntimeError(f"P5 orchestrator version 响应异常: {body}")


def gate_p1() -> dict:
    data = api_json("GET", "/api/prompts/catalog")
    if not isinstance(data, dict):
        raise RuntimeError("P1 catalog 响应非对象")
    version = data.get("catalogVersion")
    if version is None or int(version) < 1:
        raise RuntimeError(f"P1 catalogVersion 应 >=1，实际={version}")
    entries = data.get("entries") or []
    ids = {e.get("id") for e in entries if isinstance(e, dict)}
    kinds = {e.get("kind") for e in entries if isinstance(e, dict)}
    if "routing-rule" not in kinds:
        raise RuntimeError("P1 catalog 缺少 kind=routing-rule")
    if "system-prompt" not in ids and "mode-overlay.react" not in ids:
        raise RuntimeError("P1 catalog 缺少 system-prompt 或 mode-overlay.react")
    for required in (
        "memory.layer-prompt",
        "memory.stm.header",
        "memory.stm.preamble",
        "memory.current-user-marker",
    ):
        if required not in ids:
            raise RuntimeError(f"P1 catalog 缺少 {required}（TD-133）")
    print(
        f"  [OK] P1 catalogVersion={version} entries={len(entries)} "
        f"routing-rules={sum(1 for e in entries if e.get('kind') == 'routing-rule')}"
    )
    return data


def dry_run(query: str) -> dict:
    data = api_json(
        "POST",
        "/api/prompts/routing/dry-run",
        json={"query": query, "includeL0Hints": False},
    )
    if not isinstance(data, dict):
        raise RuntimeError("dry-run 响应非对象")
    return data


def gate_p2() -> dict:
    data = dry_run(STRUCTURAL_QUERY)
    matched = data.get("matchedRuleId") or ""
    would_llm = bool(data.get("wouldLlm"))
    ok_structural = "structural" in matched.lower()
    ok_rule = (not would_llm) and bool(matched)
    if not (ok_structural or ok_rule):
        raise RuntimeError(
            f"P2 dry-run 未命中规则: matchedRuleId={matched!r} wouldLlm={would_llm} stage={data.get('stage')}"
        )
    print(
        f"  [OK] P2 dry-run query={STRUCTURAL_QUERY!r} → "
        f"matched={matched!r} wouldLlm={would_llm} stage={data.get('stage')}"
    )
    return data


def get_prompt(prompt_id: str) -> dict:
    data = api_json("GET", f"/api/prompts/{prompt_id}")
    if not isinstance(data, dict):
        raise RuntimeError(f"GET {prompt_id} 非对象")
    return data


def put_priority(prompt_id: str, *, display_name: str, description: str | None, priority: int) -> dict:
    body = {
        "displayName": display_name,
        "description": description,
        "priority": priority,
    }
    return api_json("PUT", f"/api/prompts/{prompt_id}", json=body)


def gate_p3() -> None:
    detail = get_prompt(FINANCE_LIST_ID)
    original_priority = int(detail.get("priority") or 0)
    display_name = detail.get("displayName") or FINANCE_LIST_ID
    description = detail.get("description")
    baseline = dry_run(OVERLAP_QUERY)
    baseline_hit = baseline.get("matchedRuleId")
    if not baseline_hit:
        raise RuntimeError(f"P3 baseline dry-run 无命中: {baseline}")
    print(f"  [..] P3 baseline hit={baseline_hit!r} priority({FINANCE_LIST_ID})={original_priority}")

    bumped = max(original_priority + 100, 200)
    put_priority(
        FINANCE_LIST_ID,
        display_name=display_name,
        description=description,
        priority=bumped,
    )
    after = get_prompt(FINANCE_LIST_ID)
    if int(after.get("priority") or 0) != bumped:
        raise RuntimeError(f"P3 PUT priority 未生效: want={bumped} got={after.get('priority')}")

    validate = api_json("POST", "/api/prompts/routing/validate", json={})
    warnings = (validate or {}).get("warnings") if isinstance(validate, dict) else None
    print(f"  [OK] P3 validate warnings={len(warnings or [])}")

    bumped_run = dry_run(OVERLAP_QUERY)
    bumped_hit = bumped_run.get("matchedRuleId")
    if not bumped_hit or bumped_hit == baseline_hit:
        put_priority(
            FINANCE_LIST_ID,
            display_name=display_name,
            description=description,
            priority=original_priority,
        )
        raise RuntimeError(
            f"P3 dry-run 命中必须变化: baseline={baseline_hit!r} after={bumped_hit!r}"
        )
    print(f"  [OK] P3 dry-run 命中变化 {baseline_hit!r} → {bumped_hit!r}")

    put_priority(
        FINANCE_LIST_ID,
        display_name=display_name,
        description=description,
        priority=original_priority,
    )
    restored = get_prompt(FINANCE_LIST_ID)
    if int(restored.get("priority") or 0) != original_priority:
        raise RuntimeError(
            f"P3 恢复 priority 失败: want={original_priority} got={restored.get('priority')}"
        )
    print(f"  [OK] P3 priority 已恢复为 {original_priority}")


def gate_p4() -> int:
    """对 finance-list：发布新版本（内容同 active）再 rollback；返回 bump 后的 catalogVersion。"""
    detail = get_prompt(FINANCE_LIST_ID)
    active_before = int(detail.get("activeVersion") or 0)
    active_content = detail.get("activeVersionContent") or {}
    content_json = active_content.get("contentJson")
    content_text = active_content.get("contentText")
    if not content_json and not content_text:
        catalog = api_json("GET", "/api/prompts/catalog")
        entry = next(
            (e for e in (catalog.get("entries") or []) if e.get("id") == FINANCE_LIST_ID),
            None,
        )
        if not entry:
            raise RuntimeError(f"P4 catalog 无 {FINANCE_LIST_ID}")
        content_json = entry.get("contentJson")
        content_text = entry.get("contentText")

    version_before = catalog_version()
    version_item = api_json(
        "POST",
        f"/api/prompts/{FINANCE_LIST_ID}/versions",
        json={
            "status": "draft",
            "contentJson": content_json,
            "contentText": content_text,
            "changeNote": "verify_prompt_catalog_live P4 temp",
            "maintainer": "verify_prompt_catalog_live",
        },
    )
    new_ver = int(version_item.get("version") or 0)
    if new_ver <= 0:
        raise RuntimeError(f"P4 addVersion 无 version: {version_item}")

    published = api_json(
        "POST",
        f"/api/prompts/{FINANCE_LIST_ID}/publish",
        json={"version": new_ver, "maintainer": "verify_prompt_catalog_live"},
    )
    if int(published.get("activeVersion") or 0) != new_ver:
        raise RuntimeError(f"P4 publish 后 activeVersion 非 {new_ver}: {published.get('activeVersion')}")
    print(f"  [OK] P4 published version={new_ver} (was {active_before})")

    version_after_publish = catalog_version()
    if version_after_publish <= version_before:
        raise RuntimeError(
            f"P4 publish 后 catalogVersion 应升高: before={version_before} after={version_after_publish}"
        )

    rolled = api_json(
        "POST",
        f"/api/prompts/{FINANCE_LIST_ID}/rollback",
        json={"version": active_before},
    )
    if int(rolled.get("activeVersion") or 0) != active_before:
        raise RuntimeError(
            f"P4 rollback 后 activeVersion 应为 {active_before}，实际={rolled.get('activeVersion')}"
        )
    print(f"  [OK] P4 rollback → activeVersion={active_before}")
    return catalog_version()


def gate_p5(target_version: int) -> None:
    """硬门：orchestrator 本地 Snapshot 追上 prompt-manager catalogVersion。"""
    try:
        r = requests.get(f"{_ORCH}/health", timeout=3)
        if r.status_code >= 500:
            raise RuntimeError(f"P5 orchestrator health HTTP {r.status_code}")
    except requests.RequestException as exc:
        raise RuntimeError(f"P5 orchestrator 不可达: {exc}") from exc

    deadline = time.time() + (REFRESH_MS / 1000.0) * 3 + 2.0
    last = -1
    while time.time() < deadline:
        try:
            last = orch_catalog_version()
            if last >= target_version:
                print(
                    f"  [OK] P5 orchestrator catalogVersion={last} "
                    f">= prompt-manager={target_version} (refresh-ms={REFRESH_MS})"
                )
                return
        except (requests.RequestException, RuntimeError, ValueError, KeyError) as exc:
            last_err = str(exc)
        else:
            last_err = None
        time.sleep(0.5)
    raise RuntimeError(
        f"P5 orchestrator catalogVersion 未追上: want>={target_version} last={last} err={last_err}"
    )


def gate_p6() -> None:
    """golden-set 子集：dry-run 句式 → 预期规则 id 前缀。"""
    cases = [
        (STRUCTURAL_QUERY, "structural", "A structural"),
        (FINANCE_LIST_QUERY, "finance-list", "B finance-list"),
        (PEER_QUERY, "peer", "E peer"),
        (REACT_POLICY_QUERY, "react-policy", "react-policy-qa"),
    ]
    for query, needle, label in cases:
        data = dry_run(query)
        matched = (data.get("matchedRuleId") or "").lower()
        if needle not in matched:
            raise RuntimeError(
                f"P6 {label} dry-run 未命中含 {needle!r}: query={query!r} matched={data.get('matchedRuleId')!r}"
            )
        print(f"  [OK] P6 {label} → {data.get('matchedRuleId')}")


def main() -> int:
    parser = argparse.ArgumentParser(description="4.11 Prompt Catalog Live 验收")
    parser.add_argument("--prompt-url", default=DEFAULT_PROMPT_URL, help="prompt-manager base URL")
    parser.add_argument(
        "--orchestrator-url", default=DEFAULT_ORCH_URL, help="orchestrator base URL（P5）"
    )
    args = parser.parse_args()

    global _BASE, _ORCH
    _BASE = args.prompt_url.rstrip("/")
    _ORCH = args.orchestrator_url.rstrip("/")

    print(f"=== 4.11 Prompt Catalog Live === prompt-manager={_BASE}")
    if not service_ready(_BASE):
        print(f"[FAIL] prompt-manager 不可达: {_BASE}/api/prompts/catalog", file=sys.stderr)
        print("  hint: 确认 :8500 已启动（python scripts/start.py 或单独起 prompt-manager）", file=sys.stderr)
        return 1

    try:
        print("[P1] GET /api/prompts/catalog")
        gate_p1()
        print("[P2] POST /api/prompts/routing/dry-run")
        gate_p2()
        print("[P3] priority adjust + restore（命中必须变化）")
        gate_p3()
        print("[P4] publish + rollback")
        bumped_version = gate_p4()
        print("[P5] orchestrator catalogVersion 热更新（硬门）")
        gate_p5(bumped_version)
        print("[P6] golden-set 子集 dry-run")
        gate_p6()
    except (requests.RequestException, RuntimeError, ValueError, json.JSONDecodeError, KeyError) as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return 1

    print("[PASS] 4.11 Prompt Catalog Live (P1–P6)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
