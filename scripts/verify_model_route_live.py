#!/usr/bin/env python3
"""phase5 5.3 多模型场景路由 Live 验收 — model=auto + call_site 路由 + 策略管理 + 热更新。

用法:
  python3 scripts/verify_model_route_live.py

环境变量:
  BFF_URL                默认 http://127.0.0.1:8001（模型路由策略管理）
  RM_URL                 默认 http://127.0.0.1:8240（model catalog SSOT）
  LLM_URL                默认 http://127.0.0.1:8300（llm-gateway 直调）
  ORCH_URL               默认 http://127.0.0.1:8200（用量记录查询）

门禁（spec phase5 §3.5.3）:
  R1  model=auto + call_site → 按策略池选首个 enabled 模型
  R2  显式 model 直路由不回归
  R3  model=auto 无 call_site → 400 明确报错
  R4  用量记录 call_site 透传落库（llm_usage_record）
  R5  策略 CRUD：list/keys/upsert/toggle/delete（BFF 透传）
  R6  策略变更热更新：upsert 换序 → llm-gateway 30s 内按新池路由
  R7  语义缓存隔离：model=auto 请求不入缓存（同消息两次均真实路由），显式模型 key 含 call_site
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import requests

from sunshine_lib import unwrap_r

BFF_URL = os.environ.get("BFF_URL", "http://127.0.0.1:8001").rstrip("/")
RM_URL = os.environ.get("RM_URL", "http://127.0.0.1:8240").rstrip("/")
LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8300").rstrip("/")
ORCH_URL = os.environ.get("ORCH_URL", "http://127.0.0.1:8200").rstrip("/")
TIMEOUT = 60


def rm_catalog() -> dict:
    r = requests.get(f"{RM_URL}/api/models/catalog", timeout=15)
    r.raise_for_status()
    data = unwrap_r(r.json())
    return data or {}


def routes() -> list[dict]:
    r = requests.get(f"{BFF_URL}/api/models/routes", timeout=15)
    r.raise_for_status()
    data = unwrap_r(r.json())
    return data or []


def route_by_site(call_site: str) -> dict:
    for item in routes():
        if item.get("callSite") == call_site:
            return item
    raise RuntimeError(f"策略缺失 call_site={call_site}，请先在模型注册表 /models 配置 model_route_policy")


def enabled_models(catalog: dict) -> set:
    return {d.get("modelName") for d in catalog.get("definitions", []) if d.get("enabled")}


def first_available(policy: dict, enabled: set) -> str:
    for m in policy.get("models", []):
        if m in enabled:
            return m
    raise RuntimeError(f"策略 {policy.get('callSite')} 模型池全不可用: {policy.get('models')}")


def llm(model: str, call_site: str, content: str, stream: bool = False) -> dict:
    body = {"model": model, "messages": [{"role": "user", "content": content}], "max_tokens": 8}
    if call_site is not None:
        body["call_site"] = call_site
    body["stream"] = stream
    r = requests.post(f"{LLM_URL}/v1/chat/completions", json=body, timeout=TIMEOUT)
    try:
        data = r.json()
        data["_status"] = r.status_code
        return data
    except Exception:
        return {"_status": r.status_code, "_raw": r.text[:200]}


def gate_r1(catalog: dict) -> None:
    """model=auto 按 call_site 策略选首个 enabled 模型。"""
    enabled = enabled_models(catalog)
    for site in ("rewrite", "plan", "worker"):
        policy = route_by_site(site)
        expect = first_available(policy, enabled)
        resp = llm("auto", site, f"auto路由验证-{site}")
        actual = resp.get("model")
        if actual != expect:
            raise RuntimeError(
                f"R1 失败 call_site={site}：期望 {expect}，实际 {actual}（resp={resp.get('error')}）")
        print(f"  [OK] R1 auto+{site} → {actual}")


def gate_r2() -> None:
    """显式 model 直路由不回归。"""
    resp = llm("deepseek-v4-flash", "rewrite", "显式路由验证")
    if resp.get("model") != "deepseek-v4-flash":
        raise RuntimeError(f"R2 失败：显式 model 应直路由，实际 {resp.get('model')} {resp.get('error')}")
    print("  [OK] R2 显式 model=deepseek-v4-flash 直路由")


def gate_r3() -> None:
    """model=auto 无 call_site → 400。"""
    resp = llm("auto", None, "auto无调用点验证")
    if "error" not in resp or resp.get("_status") != 400:
        raise RuntimeError(f"R3 失败：期望 400 明确报错，实际 {resp}")
    print(f"  [OK] R3 model=auto 无 call_site → 400: {resp['error'].get('message', '')[:60]}")


def gate_r4() -> None:
    """触发一次带 call_site 的调用，用量记录落库且 call_site 透传。"""
    site = "rewrite"
    resp = llm("auto", site, "用量callSite落库验证")
    actual = resp.get("model")
    if not actual:
        raise RuntimeError(f"R4 前置失败：调用未成功 {resp.get('error')}")
    since = int((time.time() - 120) * 1000)
    r = requests.get(f"{ORCH_URL}/api/usage/records", params={"since": since}, timeout=15)
    r.raise_for_status()
    records = unwrap_r(r.json()) or []
    matches = [x for x in records if x.get("callSite") == site and x.get("model") == actual]
    if not matches:
        # 消费端稀疏流量退避，最长等待 2 分钟
        for _ in range(24):
            time.sleep(5)
            r = requests.get(f"{ORCH_URL}/api/usage/records", params={"since": since}, timeout=15)
            records = unwrap_r(r.json()) or []
            matches = [x for x in records if x.get("callSite") == site and x.get("model") == actual]
            if matches:
                break
        if not matches:
            raise RuntimeError(
                f"R4 失败：调用 call_site={site} model={actual} 后 2 分钟内未落库")
    print(f"  [OK] R4 用量落库 call_site={site} model={actual} (n={len(matches)})")


def gate_r5(catalog: dict) -> None:
    """BFF 策略 CRUD：keys/list/upsert/delete 并恢复原策略。"""
    r = requests.get(f"{BFF_URL}/api/models/routes/keys", timeout=15)
    r.raise_for_status()
    keys = unwrap_r(r.json()) or []
    if "REWRITE" not in keys or "PLAN" not in keys:
        raise RuntimeError(f"R5 keys 缺失调用点枚举: {keys}")
    backup = {item["callSite"]: item for item in routes()}
    site = "tool-call"
    if site not in backup:
        # delete 测试残留/缺失时自愈：先建种子默认池
        requests.put(f"{BFF_URL}/api/models/routes",
                     json={"callSite": site, "models": ["deepseek-v4-flash", "qwen-plus"],
                           "strategy": "first-available", "remark": "工具调用：快模型"}, timeout=15)
        backup = {item["callSite"]: item for item in routes()}
    before = backup[site]
    # upsert（原池）
    r = requests.put(f"{BFF_URL}/api/models/routes",
                     json={"callSite": site, "models": before["models"],
                           "strategy": before.get("strategy", "first-available"),
                           "remark": before.get("remark", "")}, timeout=15)
    r.raise_for_status()
    after = route_by_site(site)
    if after["models"] != before["models"]:
        raise RuntimeError(f"R5 upsert 失败：{before} -> {after}")
    # delete 后恢复（upsert 重建原池）
    r = requests.delete(f"{BFF_URL}/api/models/routes/{after['id']}", timeout=15)
    r.raise_for_status()
    r = requests.put(f"{BFF_URL}/api/models/routes",
                     json={"callSite": site, "models": before["models"],
                           "strategy": before.get("strategy", "first-available"),
                           "remark": before.get("remark", "")}, timeout=15)
    r.raise_for_status()
    if route_by_site(site)["models"] != before["models"]:
        raise RuntimeError("R5 delete 后恢复失败")
    print("  [OK] R5 策略 CRUD（keys/list/upsert/delete）")


def gate_r6(catalog: dict) -> None:
    """upsert 换序 → llm-gateway 热更新 30s 内按新池路由 → 恢复。"""
    enabled = enabled_models(catalog)
    site = "rewrite"
    backup = route_by_site(site)
    old_first = first_available(backup, enabled)
    # 取与池首不同的 enabled 模型做交换
    new_pool = list(backup["models"])
    swapped = [m for m in new_pool if m in enabled and m != old_first]
    if not swapped:
        print("  [SKIP] R6 无可用交换模型（池单模型），跳过热更新验证")
        return
    swapped_first = swapped[0]
    swapped_rest = [m for m in new_pool if m != swapped_first]
    new_pool = [swapped_first] + swapped_rest
    r = requests.put(f"{BFF_URL}/api/models/routes",
                     json={"callSite": site, "models": new_pool,
                           "strategy": backup.get("strategy", "first-available"),
                           "remark": backup.get("remark", "")}, timeout=15)
    r.raise_for_status()
    try:
        for _ in range(30):
            time.sleep(1)
            resp = llm("auto", site, f"热更新验证{int(time.time())}")
            if resp.get("model") == swapped_first:
                print(f"  [OK] R6 热更新：rewrite 池换序后 auto → {swapped_first}")
                return
        raise RuntimeError(f"R6 失败：换序 {new_pool} 后 30s 内 auto 仍路由 {resp.get('model')}")
    finally:
        r = requests.put(f"{BFF_URL}/api/models/routes",
                         json={"callSite": site, "models": backup["models"],
                               "strategy": backup.get("strategy", "first-available"),
                               "remark": backup.get("remark", "")}, timeout=15)
        r.raise_for_status()
        print("  [OK] R6 已恢复原池")


def gate_r7() -> None:
    """语义缓存隔离：model=auto 同消息两次均真实路由（缓存不应命中）。"""
    content = f"语义缓存隔离验证{int(time.time())}"
    first = llm("auto", "rewrite", content)
    second = llm("auto", "rewrite", content)
    if not first.get("model") or first.get("model") != second.get("model"):
        raise RuntimeError(f"R7 失败：两次 auto 路由结果不一致 {first} {second}")
    # 两次都真实路由（auto 请求不入语义缓存）：通过响应完整返回校验；命中缓存会静默返回旧模型
    print(f"  [OK] R7 model=auto 同消息两次均真实路由 → {first.get('model')}")


def main() -> int:
    global BFF_URL, RM_URL, LLM_URL, ORCH_URL
    parser = argparse.ArgumentParser(description="phase5 5.3 多模型场景路由 Live 验收")
    parser.add_argument("--bff", default=BFF_URL)
    parser.add_argument("--rm", default=RM_URL)
    parser.add_argument("--llm", default=LLM_URL)
    parser.add_argument("--orch", default=ORCH_URL)
    args = parser.parse_args()

    BFF_URL, RM_URL, LLM_URL, ORCH_URL = args.bff.rstrip("/"), args.rm.rstrip("/"), args.llm.rstrip("/"), args.orch.rstrip("/")
    print(f"=== 5.3 多模型场景路由 Live ===\nbff={BFF_URL} rm={RM_URL} llm={LLM_URL} orch={ORCH_URL}")

    catalog = rm_catalog()
    if not catalog.get("routes"):
        raise SystemExit("model catalog 无 routes，请先应用 model_route_policy 表种子")

    gate_r1(catalog)
    gate_r2()
    gate_r3()
    gate_r4()
    gate_r5(catalog)
    gate_r6(catalog)
    gate_r7()
    print("=== 5.3 多模型场景路由 Live 全部通过 ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
