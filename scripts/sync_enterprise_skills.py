#!/usr/bin/env python3
"""将 docs/skills 企业 Skill 同步到 Live，并删除 demo 遗留。

用法:
  python3 scripts/sync_enterprise_skills.py
  python3 scripts/sync_enterprise_skills.py --delete-only
  GATEWAY_URL=http://127.0.0.1:8000 python3 scripts/sync_enterprise_skills.py

环境变量:
  GATEWAY_URL  默认 http://127.0.0.1:8000
"""
from __future__ import annotations

import argparse
import io
import os
import sys
import uuid
import zipfile
from pathlib import Path

import requests

from sunshine_lib import ROOT, unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
SKILLS_ROOT = ROOT / "docs" / "skills"

# 必须从 Live 清除的 demo / 结构演示包
DELETE_IDS = ("demo-full-pack",)

# 企业技能（目录名 = skill id）；sandbox 保留历史 id 供 4.5 Live 验收
ENTERPRISE_SKILLS: list[dict] = [
    {
        "id": "finance-analysis",
        "displayName": "财务合规分析",
        "description": "报销/费用单据与企业制度的内部合规分析（对齐 corpus-50）",
    },
    {
        "id": "policy-review",
        "displayName": "制度审查",
        "description": "企业多域制度条款解读（人事/财务/安全/IT 等，对齐 corpus-50）",
    },
    {
        "id": "compliance-check",
        "displayName": "合规对比",
        "description": "制度片段与业务数据逐项合规对比（对齐 corpus-50）",
    },
    {
        "id": "finance-report",
        "displayName": "财务数据解读",
        "description": "本人费用汇总与待办构成的解读（对齐企业工具与 corpus-50）",
    },
    {
        "id": "knowledge-brief",
        "displayName": "知识要点提炼",
        "description": "corpus-50 企业知识检索结果的要点提炼与结构化摘要",
    },
    {
        "id": "sandbox-coding-demo",
        "displayName": "工作区沙箱编程",
        "description": "企业工作区沙箱编程（读 /skills/{id}、写 /workspace、exec）",
        "sandbox": "docker",
        "sandboxPolicy": {
            "runtime": "docker",
            "image": "sunshine-sandbox-python:3.11-slim",
            "timeoutSec": 30,
            "memoryMb": 256,
            "cpus": 0.5,
            "networkAllow": [],
            "execReadonlyAllow": ["ls *", "pwd", "python -m pytest *", "python /skills/*/scripts/*"],
        },
    },
    {
        "id": "compliance-review",
        "displayName": "费用合规审查",
        "description": "报销合规对照场景：命中时装载费用制度 Policy",
    },
    {
        "id": "expense-assist",
        "displayName": "报销助手",
        "description": "报销查询/提交辅助场景",
    },
    {
        "id": "travel-budget",
        "displayName": "差旅预算",
        "description": "差旅额度与预算管控场景",
    },
]


def auth_headers() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"sync_sk_{suffix}"
    password = "password123"
    reg = requests.post(
        f"{GATEWAY_URL}/api/auth/register",
        json={"username": username, "password": password},
        timeout=30,
    )
    reg.raise_for_status()
    login = requests.post(
        f"{GATEWAY_URL}/api/auth/login",
        json={"username": username, "password": password},
        timeout=30,
    )
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.json()}")
    return {"Authorization": f"Bearer {token}"}


def api_json(method: str, path: str, headers: dict, **kwargs):
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, timeout=120, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def build_zip(skill_dir: Path) -> bytes:
    if not (skill_dir / "SKILL.md").is_file():
        raise FileNotFoundError(f"缺少 {skill_dir / 'SKILL.md'}")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(skill_dir.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(skill_dir).as_posix())
    return buf.getvalue()


def list_skill_ids(headers: dict) -> set[str]:
    skills = api_json("GET", "/api/skills", {**headers, "Content-Type": "application/json"})
    return {s.get("id") for s in skills if s.get("id")}


def delete_demo(headers: dict) -> None:
    json_headers = {**headers, "Content-Type": "application/json"}
    existing = list_skill_ids(headers)
    for skill_id in DELETE_IDS:
        if skill_id not in existing:
            print(f"[OK] 已不存在: {skill_id}")
            continue
        api_json("DELETE", f"/api/skills/{skill_id}", json_headers)
        print(f"[OK] DELETE {skill_id}")


def upsert_skill(headers: dict, meta: dict) -> None:
    skill_id = meta["id"]
    skill_dir = SKILLS_ROOT / skill_id
    if not skill_dir.is_dir():
        raise FileNotFoundError(f"缺少目录 {skill_dir}")
    json_headers = {**headers, "Content-Type": "application/json"}
    existing = list_skill_ids(headers)
    body = {
        "id": skill_id,
        "displayName": meta["displayName"],
        "description": meta["description"],
    }
    if meta.get("sandbox"):
        body["sandbox"] = meta["sandbox"]
        body["sandboxPolicy"] = meta.get("sandboxPolicy")
    if skill_id not in existing:
        api_json("POST", "/api/skills", json_headers, json=body)
        print(f"[OK] POST /api/skills {skill_id}")
    else:
        api_json("PUT", f"/api/skills/{skill_id}", json_headers, json={
            "displayName": meta["displayName"],
            "description": meta["description"],
        })
        print(f"[OK] PUT 元数据 {skill_id}")

    zip_bytes = build_zip(skill_dir)
    up = requests.post(
        f"{GATEWAY_URL}/api/skills/{skill_id}/upload",
        headers=headers,
        files={"file": (f"{skill_id}.zip", zip_bytes, "application/zip")},
        timeout=120,
    )
    up.raise_for_status()
    uploaded = unwrap_r(up.json(), context="upload")
    version = uploaded.get("version") if isinstance(uploaded, dict) else None
    if version is None:
        versions = api_json("GET", f"/api/skills/{skill_id}/versions", json_headers)
        version = max(v["version"] for v in versions)
    print(f"[OK] upload {skill_id} -> v{version}")

    api_json(
        "POST",
        f"/api/skills/{skill_id}/publish",
        json_headers,
        params={"version": version},
    )
    print(f"[OK] publish {skill_id} v{version}")

    if meta.get("sandbox"):
        api_json(
            "PUT",
            f"/api/skills/{skill_id}/versions/{version}/sandbox",
            json_headers,
            json={"sandbox": meta["sandbox"], "sandboxPolicy": meta.get("sandboxPolicy")},
        )
        print(f"[OK] sandbox={meta['sandbox']} on {skill_id} v{version}")

    api_json(
        "PUT",
        f"/api/skills/{skill_id}/enable",
        json_headers,
        json={"enabled": True},
    )
    print(f"[OK] enabled {skill_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync enterprise skills to Live")
    parser.add_argument("--delete-only", action="store_true", help="仅删除 demo 技能")
    args = parser.parse_args()

    print(f"=== Sync enterprise skills === Gateway={GATEWAY_URL}")
    headers = auth_headers()
    delete_demo(headers)
    if args.delete_only:
        return 0

    for meta in ENTERPRISE_SKILLS:
        upsert_skill(headers, meta)

    remaining = sorted(list_skill_ids(headers))
    print()
    print(f"Live skills ({len(remaining)}): {', '.join(remaining)}")
    for bad in DELETE_IDS:
        if bad in remaining:
            print(f"[FAIL] demo 仍残留: {bad}", file=sys.stderr)
            return 1
    print("[OK] demo-full-pack 已清除，企业技能已发布启用")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        raise SystemExit(1)
