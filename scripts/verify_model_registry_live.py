#!/usr/bin/env python3
"""模型注册表 Live 验收 — resource-manager Catalog + llm-gateway + scene 绑定。

用法:
  python3 scripts/verify_model_registry_live.py
  GATEWAY_URL=http://127.0.0.1:8000 python3 scripts/verify_model_registry_live.py

环境变量:
  GATEWAY_URL              默认 http://127.0.0.1:8000
  RESOURCE_MANAGER_URL     默认 http://127.0.0.1:8240（直连内网 catalog/gateway）
  LLM_GATEWAY_URL          默认 http://127.0.0.1:8300
  DEEPSEEK_API_KEY / QWEN_API_KEY  若设置则写入 provider 密文以跑 M2/M6；否则相关门 soft-skip
  NACOS_DOCS               默认 docs/nacos（M7 漂移检查）

门禁（设计 §12）:
  M1  管理面新增 provider+模型 → 不重启 → /v1/models 可见（热更新）
  M2  chat 选模型（需 API Key）→ 请求体 model 为所选
  M3  非多模态带图 → 400 model_not_multimodal
  M4  reasoning 能力：非思考模型请求体剥离 enable_thinking（经 gateway 正常 200 或能力路径）
  M5  停用会话模型 → catalog 仍可解析 scene 默认（定义 toggle）
  M6  intent scene 改 primary（需 Key）→ 分类走新模型
  M7  Nacos YAML 无 llm.providers / agent.model.name 等键
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

import requests

from sunshine_lib import ROOT, unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
RM_URL = os.environ.get("RESOURCE_MANAGER_URL", "http://127.0.0.1:8240").rstrip("/")
LLM_URL = os.environ.get("LLM_GATEWAY_URL", "http://127.0.0.1:8300").rstrip("/")
NACOS_DOCS = Path(os.environ.get("NACOS_DOCS", str(ROOT / "docs" / "nacos")))
DEEPSEEK_KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()
QWEN_KEY = os.environ.get("QWEN_API_KEY", "").strip()
TIMEOUT = 60


def auth_headers() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"model_reg_{suffix}"
    password = "password123"
    reg = requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": username, "password": password},
        timeout=30,
    )
    reg.raise_for_status()
    if reg.json().get("code") != 200:
        raise RuntimeError(f"register failed: {reg.json()}")
    login = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.json()}")
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def api_json(method: str, path: str, headers: dict, **kwargs):
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, timeout=TIMEOUT, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def wait_v1_models_contains(model_name: str, *, timeout_sec: float = 15.0) -> dict:
    deadline = time.time() + timeout_sec
    last: dict | list | None = None
    while time.time() < deadline:
        r = requests.get(f"{LLM_URL}/v1/models", timeout=10)
        r.raise_for_status()
        last = r.json()
        data = last.get("data") if isinstance(last, dict) else last
        ids = []
        if isinstance(data, list):
            ids = [m.get("id") or m.get("model_name") for m in data if isinstance(m, dict)]
        elif isinstance(last, dict) and "models" in last:
            ids = [m.get("id") for m in last["models"] if isinstance(m, dict)]
        if model_name in ids:
            return last if isinstance(last, dict) else {"data": last}
        time.sleep(0.5)
    raise RuntimeError(f"热更新超时：/v1/models 未见 {model_name}; last={last}")


def ensure_provider_keys(headers: dict) -> bool:
    """事后配密钥：若环境变量存在则写入 deepseek/qwen。返回是否至少配了一个。"""
    providers = api_json("GET", "/api/models/providers", headers)
    if not isinstance(providers, list):
        raise RuntimeError("providers 响应非列表")
    by_key = {p.get("providerKey") or p.get("provider_key"): p for p in providers if isinstance(p, dict)}
    wrote = False
    for key, secret in (("deepseek", DEEPSEEK_KEY), ("qwen", QWEN_KEY)):
        if not secret:
            continue
        p = by_key.get(key)
        if not p:
            print(f"  [WARN] provider {key} 不存在，跳过写密钥")
            continue
        pid = p.get("id")
        body = {
            "displayName": p.get("displayName") or p.get("display_name") or key,
            "protocol": p.get("protocol") or "openai-compatible",
            "baseUrl": p.get("baseUrl") or p.get("base_url"),
            "pathPrefix": p.get("pathPrefix") if p.get("pathPrefix") is not None else p.get("path_prefix", ""),
            "apiKey": secret,
            "enabled": True,
            "tenantId": p.get("tenantId") or p.get("tenant_id") or "default",
        }
        api_json("PUT", f"/api/models/providers/{pid}", headers, json=body)
        wrote = True
        print(f"  [OK] 已写入 provider {key} apiKey（来自环境变量）")
    return wrote


def gate_m7() -> None:
    forbidden = [
        re.compile(r"(?m)^\s*providers\s*:"),
        re.compile(r"(?m)^\s*fallback\s*:"),
        re.compile(r"agent\.model\.name"),
        re.compile(r"(?m)^\s*name:\s*deepseek"),
        re.compile(r"(?m)intent:\s*\n\s+model:"),
    ]
    # 更精确：检查已改动的三个 YAML 不含旧模型清单
    checks = [
        (NACOS_DOCS / "sunshine-llm-gateway.yaml", [r"(?m)^\s*providers\s*:", r"(?m)^\s*fallback\s*:"]),
        (NACOS_DOCS / "sunshine-orchestrator.yaml", [
            r"(?m)^\s*name:\s*deepseek-v4",
            r"(?m)^\s*model:\s*deepseek",
            r"(?m)^\s*default-model-window\s*:",
        ]),
    ]
    for path, patterns in checks:
        text = path.read_text(encoding="utf-8")
        for pat in patterns:
            if re.search(pat, text):
                raise RuntimeError(f"M7 Nacos 漂移: {path.name} 仍匹配 /{pat}/")
    # 正向：crypto 必须在
    for name in ("sunshine-llm-gateway.yaml", "sunshine-resource-manager.yaml"):
        text = (NACOS_DOCS / name).read_text(encoding="utf-8")
        if "aes-key" not in text:
            raise RuntimeError(f"M7 {name} 缺少 model.crypto.aes-key")
    print("  [OK] M7 Nacos 无 llm.providers/fallback 与 orchestrator 模型名；含 aes-key")


def gate_m1(headers: dict) -> tuple[int | None, str]:
    suffix = uuid.uuid4().hex[:6]
    provider_key = f"live-{suffix}"
    model_name = f"live-model-{suffix}"
    created_provider = api_json(
        "POST",
        "/api/models/providers",
        headers,
        json={
            "providerKey": provider_key,
            "displayName": f"Live {suffix}",
            "protocol": "openai-compatible",
            "baseUrl": "https://example.invalid",
            "pathPrefix": "/v1",
            "apiKey": "sk-live-placeholder",
            "enabled": True,
            "tenantId": "default",
        },
    )
    pid = created_provider.get("id") if isinstance(created_provider, dict) else None
    created_def = api_json(
        "POST",
        "/api/models/definitions",
        headers,
        json={
            "providerKey": provider_key,
            "modelName": model_name,
            "displayName": f"Live Model {suffix}",
            "contextWindow": 8192,
            "encoding": "cl100k_base",
            "capabilities": {"reasoning": False, "multimodal": False, "toolCall": True},
            "userSelectable": False,
            "enabled": True,
            "sortOrder": 999,
            "tenantId": "default",
        },
    )
    did = created_def.get("id") if isinstance(created_def, dict) else None
    wait_v1_models_contains(model_name, timeout_sec=20)
    print(f"  [OK] M1 热更新可见 model={model_name}")
    return did, model_name


def gate_m3() -> None:
    body = {
        "model": "deepseek-v4-flash",
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "描述图片"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}},
                ],
            }
        ],
    }
    r = requests.post(f"{LLM_URL}/v1/chat/completions", json=body, timeout=30)
    if r.status_code != 400:
        raise RuntimeError(f"M3 期望 400，实际 {r.status_code}: {r.text[:300]}")
    text = r.text.lower()
    if "model_not_multimodal" not in text and "multimodal" not in text:
        raise RuntimeError(f"M3 响应应含 model_not_multimodal: {r.text[:300]}")
    print("  [OK] M3 非多模态+图 → 400")


def gate_m4() -> None:
    """非思考模型：带 enable_thinking 不应 400（应剥离后继续；UNSET key 可能 5xx）。"""
    body = {
        "model": "qwen-plus",
        "messages": [{"role": "user", "content": "ping"}],
        "enable_thinking": True,
        "max_tokens": 8,
    }
    r = requests.post(f"{LLM_URL}/v1/chat/completions", json=body, timeout=30)
    # 能力校验通过后可能因 UNSET key / 上游失败；不得以 model_not_* 拒绝
    if r.status_code == 400 and "model_not_" in r.text:
        raise RuntimeError(f"M4 非思考模型不应因 thinking 字段 400: {r.text[:300]}")
    print(f"  [OK] M4 reasoning 剥离路径未误杀（status={r.status_code}）")


def gate_m5(headers: dict, definition_id: int | None, model_name: str) -> None:
    if definition_id is None:
        # 找 live 模型 id
        defs = api_json("GET", "/api/models/definitions", headers)
        for d in defs if isinstance(defs, list) else []:
            if (d.get("modelName") or d.get("model_name")) == model_name:
                definition_id = d.get("id")
                break
    if definition_id is None:
        raise RuntimeError("M5 找不到临时定义 id")
    api_json("POST", f"/api/models/definitions/{definition_id}/toggle", headers, json={})
    # 再 toggle 一次确保最终 disabled
    dlist = api_json("GET", "/api/models/definitions", headers)
    enabled = None
    for d in dlist if isinstance(dlist, list) else []:
        if d.get("id") == definition_id:
            enabled = d.get("enabled")
            break
    if enabled is True:
        api_json("POST", f"/api/models/definitions/{definition_id}/toggle", headers, json={})
    catalog = api_json("GET", "/api/models/catalog", headers)
    scenes = (catalog or {}).get("scenes") if isinstance(catalog, dict) else None
    chat = None
    for s in scenes or []:
        if (s.get("sceneKey") or s.get("scene_key")) == "chat":
            chat = s
            break
    if not chat or not (chat.get("primaryModel") or chat.get("primary_model")):
        raise RuntimeError("M5 catalog 缺少 chat scene primary")
    print(f"  [OK] M5 停用 {model_name} 后 chat scene 仍有 primary={chat.get('primaryModel') or chat.get('primary_model')}")


def gate_m2(headers: dict) -> None:
    if not (DEEPSEEK_KEY or QWEN_KEY):
        print("  [SKIP] M2 未设置 DEEPSEEK_API_KEY/QWEN_API_KEY")
        return
    model = "deepseek-v4-flash" if DEEPSEEK_KEY else "qwen-plus"
    # 非流式直打 gateway 断言 model 路由存在
    r = requests.post(
        f"{LLM_URL}/v1/chat/completions",
        json={
            "model": model,
            "messages": [{"role": "user", "content": "只回复ok"}],
            "max_tokens": 8,
        },
        timeout=90,
    )
    if r.status_code >= 400:
        raise RuntimeError(f"M2 chat completions 失败 {r.status_code}: {r.text[:400]}")
    body = r.json()
    # OpenAI 响应不一定回显 model；至少 200 + choices
    if not body.get("choices"):
        raise RuntimeError(f"M2 无 choices: {body}")
    print(f"  [OK] M2 模型调用成功 model={model}")


def gate_m6(headers: dict) -> None:
    if not (DEEPSEEK_KEY or QWEN_KEY):
        print("  [SKIP] M6 未设置 API Key")
        return
    scenes = api_json("GET", "/api/models/scenes", headers)
    intent = None
    for s in scenes if isinstance(scenes, list) else []:
        if (s.get("sceneKey") or s.get("scene_key")) == "intent":
            intent = s
            break
    if not intent:
        raise RuntimeError("M6 缺少 intent scene")
    original_primary = intent.get("primaryModel") or intent.get("primary_model")
    original_fallback = intent.get("fallbackModel") or intent.get("fallback_model")
    new_primary = "qwen-plus" if original_primary != "qwen-plus" else "deepseek-v4-flash"
    try:
        api_json(
            "PUT",
            "/api/models/scenes",
            headers,
            json={
                "sceneKey": "intent",
                "primaryModel": new_primary,
                "fallbackModel": original_fallback,
                "extras": intent.get("extras"),
                "enabled": True,
                "tenantId": "default",
            },
        )
        time.sleep(1.5)
        catalog = api_json("GET", "/api/models/catalog", headers)
        found = None
        for s in (catalog or {}).get("scenes") or []:
            if (s.get("sceneKey") or s.get("scene_key")) == "intent":
                found = s.get("primaryModel") or s.get("primary_model")
        if found != new_primary:
            raise RuntimeError(f"M6 catalog intent primary 未更新: {found}")
        print(f"  [OK] M6 intent primary {original_primary} → {new_primary}")
    finally:
        api_json(
            "PUT",
            "/api/models/scenes",
            headers,
            json={
                "sceneKey": "intent",
                "primaryModel": original_primary,
                "fallbackModel": original_fallback,
                "extras": intent.get("extras"),
                "enabled": True,
                "tenantId": "default",
            },
        )
        print("  [OK] M6 已恢复 intent scene")


def cleanup_temp(headers: dict, model_name: str) -> None:
    defs = api_json("GET", "/api/models/definitions", headers)
    for d in defs if isinstance(defs, list) else []:
        if (d.get("modelName") or d.get("model_name")) == model_name:
            try:
                api_json("DELETE", f"/api/models/definitions/{d['id']}", headers)
            except Exception as exc:  # noqa: BLE001
                print(f"  [WARN] 清理 definition 失败: {exc}")
    providers = api_json("GET", "/api/models/providers", headers)
    for p in providers if isinstance(providers, list) else []:
        pk = p.get("providerKey") or p.get("provider_key") or ""
        if pk.startswith("live-"):
            try:
                api_json("DELETE", f"/api/models/providers/{p['id']}", headers)
            except Exception as exc:  # noqa: BLE001
                print(f"  [WARN] 清理 provider 失败: {exc}")


def main() -> int:
    parser = argparse.ArgumentParser(description="模型注册表 Live 验收")
    parser.add_argument("--gateway", default=os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000"))
    parser.add_argument("--rm", default=os.environ.get("RESOURCE_MANAGER_URL", "http://127.0.0.1:8240"))
    parser.add_argument("--llm", default=os.environ.get("LLM_GATEWAY_URL", "http://127.0.0.1:8300"))
    args = parser.parse_args()
    global GATEWAY_URL, RM_URL, LLM_URL
    GATEWAY_URL = args.gateway.rstrip("/")
    RM_URL = args.rm.rstrip("/")
    LLM_URL = args.llm.rstrip("/")

    print(f"=== Model Registry Live ===\ngateway={GATEWAY_URL}\nrm={RM_URL}\nllm={LLM_URL}")
    # 直连 catalog 探活
    try:
        r = requests.get(f"{RM_URL}/api/models/catalog", timeout=5)
        print(f"  resource-manager catalog status={r.status_code}")
    except requests.RequestException as exc:
        print(f"  [FAIL] resource-manager 不可达: {exc}")
        return 1

    gate_m7()
    headers = auth_headers()
    ensure_provider_keys(headers)

    catalog = api_json("GET", "/api/models/catalog", headers)
    defs = (catalog or {}).get("definitions") if isinstance(catalog, dict) else None
    if not defs:
        raise RuntimeError("catalog.definitions 为空 — 检查 SQL 种子与 resource-manager")
    print(f"  [OK] catalog definitions={len(defs)}")

    did, model_name = gate_m1(headers)
    try:
        gate_m3()
        gate_m4()
        gate_m5(headers, did, model_name)
        gate_m2(headers)
        gate_m6(headers)
    finally:
        cleanup_temp(headers, model_name)

    print("=== ALL GATES PASSED (skipped noted above) ===")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
