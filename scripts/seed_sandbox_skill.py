#!/usr/bin/env python3
"""将 docs/skills/sandbox-coding-demo 入库为可跑沙箱的 Skill（4.5）。

用法:
  python3 scripts/seed_sandbox_skill.py
  GATEWAY_URL=http://ecs4c16g:8000 python3 scripts/seed_sandbox_skill.py
  python3 scripts/seed_sandbox_skill.py --force   # 已存在则仍上传新版本并设 sandbox

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
SKILL_ID = "sandbox-coding-demo"
SKILL_DIR = ROOT / "docs" / "skills" / "sandbox-coding-demo"

DEFAULT_POLICY = {
    "runtime": "docker",
    "image": "sunshine-sandbox-python:3.11-slim",
    "timeoutSec": 30,
    "memoryMb": 256,
    "cpus": 0.5,
    "networkAllow": [],
    "execReadonlyAllow": ["ls *", "pwd", "python -m pytest *", "python /skill/scripts/*"],
}


def auth_headers() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"seed_sb_{suffix}"
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


def build_zip() -> bytes:
    if not (SKILL_DIR / "SKILL.md").is_file():
        raise FileNotFoundError(f"缺少 {SKILL_DIR / 'SKILL.md'}")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(SKILL_DIR.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(SKILL_DIR).as_posix())
    return buf.getvalue()


def skill_exists(headers: dict) -> bool:
    skills = api_json("GET", "/api/skills", {**headers, "Content-Type": "application/json"})
    return any(s.get("id") == SKILL_ID for s in skills)


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed sandbox-coding-demo Skill")
    parser.add_argument("--force", action="store_true", help="已存在仍上传并刷新 sandbox")
    args = parser.parse_args()

    print(f"=== Seed {SKILL_ID} === Gateway={GATEWAY_URL}")
    if not SKILL_DIR.is_dir():
        print(f"[FAIL] 目录不存在: {SKILL_DIR}", file=sys.stderr)
        return 1

    headers = auth_headers()
    json_headers = {**headers, "Content-Type": "application/json"}
    exists = skill_exists(headers)
    if exists and not args.force:
        print(f"[OK] Skill 已存在: {SKILL_ID}（加 --force 可重传并刷新 sandbox）")
        return 0

    if not exists:
        api_json(
            "POST",
            "/api/skills",
            json_headers,
            json={
                "id": SKILL_ID,
                "displayName": "沙箱编码演示",
                "description": "4.5 Docker 沙箱 Coding Agent 示例（读 /skill、写 /workspace、exec）",
                "sandbox": "docker",
                "sandboxPolicy": DEFAULT_POLICY,
            },
        )
        print(f"[OK] POST /api/skills {SKILL_ID} sandbox=docker")

    zip_bytes = build_zip()
    up = requests.post(
        f"{GATEWAY_URL}/api/skills/{SKILL_ID}/upload",
        headers=headers,
        files={"file": (f"{SKILL_ID}.zip", zip_bytes, "application/zip")},
        timeout=120,
    )
    up.raise_for_status()
    uploaded = unwrap_r(up.json(), context="upload")
    version = uploaded.get("version") if isinstance(uploaded, dict) else None
    if version is None:
        versions = api_json("GET", f"/api/skills/{SKILL_ID}/versions", json_headers)
        version = max(v["version"] for v in versions)
    print(f"[OK] upload zip -> version={version}")

    api_json(
        "POST",
        f"/api/skills/{SKILL_ID}/publish",
        json_headers,
        params={"version": version},
    )
    print(f"[OK] publish v{version}")

    api_json(
        "PUT",
        f"/api/skills/{SKILL_ID}/versions/{version}/sandbox",
        json_headers,
        json={"sandbox": "docker", "sandboxPolicy": DEFAULT_POLICY},
    )
    print(f"[OK] sandbox=docker + policy on v{version}")

    api_json(
        "PUT",
        f"/api/skills/{SKILL_ID}/enable",
        json_headers,
        json={"enabled": True},
    )
    print(f"[OK] enabled=true")

    print()
    print("试跑:")
    print(f"  @{SKILL_ID} 请用沙箱工具：读取 /skill 下脚本，在 /workspace 写 test.txt，再 ls")
    print("或 UI: /skills → sandbox-coding-demo → 试跑")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        raise SystemExit(1)
