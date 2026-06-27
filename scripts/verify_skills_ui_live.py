#!/usr/bin/env python3
"""3.12 /skills 管理页 Live 验收 — Gateway BFF Skills Admin API。

用法:
  python3 scripts/verify_skills_ui_live.py
  GATEWAY_URL=http://localhost:8000 python3 scripts/verify_skills_ui_live.py

环境变量:
  GATEWAY_URL          默认 http://127.0.0.1:8000
  SKILL_UI_SAMPLE      读链路样例 Skill（默认 finance-analysis）
  SKILL_UI_DIFF_SAMPLE diff/下载样例（默认 demo-full-pack）

断言（skills-management-ui-design.md §7）:
  - GET /api/skills 管理列表
  - GET /api/skills/catalog/index（Chat @ 同源）
  - versions / files / file(SKILL.md) / diff / download
  - PUT 元数据 roundtrip（临时 Skill，结束后 DELETE）

浏览器路由 /skills、/skills/:skillId/diff 由 UI 手验；本脚本覆盖 API SSOT。
"""
from __future__ import annotations

import argparse
import io
import os
import sys
import uuid
import zipfile
from datetime import datetime

import requests

from sunshine_lib import unwrap_r

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8000").rstrip("/")
SAMPLE_SKILL = os.environ.get("SKILL_UI_SAMPLE", "finance-analysis")
DIFF_SKILL = os.environ.get("SKILL_UI_DIFF_SAMPLE", "demo-full-pack")

def auth_headers() -> dict[str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"skills_ui_{suffix}"
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


def api_json(method: str, path: str, headers: dict, **kwargs) -> dict | list:
    resp = requests.request(method, f"{GATEWAY_URL}{path}", headers=headers, timeout=60, **kwargs)
    resp.raise_for_status()
    return unwrap_r(resp.json(), context=path)


def check_list_and_catalog(headers: dict) -> None:
    skills = api_json("GET", "/api/skills", headers)
    if not isinstance(skills, list) or not skills:
        raise RuntimeError("GET /api/skills 应返回非空列表")
    print(f"  [OK] GET /api/skills -> {len(skills)} skills")
    index = api_json("GET", "/api/skills/catalog/index", headers)
    if not isinstance(index, list) or not index:
        raise RuntimeError("GET /api/skills/catalog/index 应返回非空")
    print(f"  [OK] GET /api/skills/catalog/index -> {len(index)} entries")


def check_read_skill(headers: dict, skill_id: str) -> None:
    versions = api_json("GET", f"/api/skills/{skill_id}/versions", headers)
    if not versions:
        raise RuntimeError(f"{skill_id} 无版本")
    version = versions[0]["version"]
    files = api_json("GET", f"/api/skills/{skill_id}/versions/{version}/files", headers)
    paths = {f.get("path") for f in files if not f.get("directory")}
    if "SKILL.md" not in paths:
        raise RuntimeError(f"{skill_id} v{version} 缺少 SKILL.md")
    content = api_json(
        "GET",
        f"/api/skills/{skill_id}/versions/{version}/file",
        headers,
        params={"path": "SKILL.md"},
    )
    body = content.get("content") if isinstance(content, dict) else None
    if not body or len(body) < 20:
        raise RuntimeError(f"{skill_id} SKILL.md 内容为空")
    print(f"  [OK] {skill_id} v{version} files={len(files)} SKILL.md chars={len(body)}")


def check_diff_and_download(headers: dict, skill_id: str) -> None:
    versions = api_json("GET", f"/api/skills/{skill_id}/versions", headers)
    nums = sorted(v["version"] for v in versions)
    if len(nums) < 2:
        api_json("POST", f"/api/skills/{skill_id}/versions/{nums[0]}/fork", headers)
        versions = api_json("GET", f"/api/skills/{skill_id}/versions", headers)
        nums = sorted(v["version"] for v in versions)
    if len(nums) < 2:
        raise RuntimeError(f"{skill_id} fork 后仍不足 2 个版本")
    diff = api_json(
        "GET",
        f"/api/skills/{skill_id}/versions/diff",
        headers,
        params={"from": nums[0], "to": nums[-1], "path": "SKILL.md"},
    )
    lines = diff.get("lines") if isinstance(diff, dict) else None
    if lines is None:
        raise RuntimeError(f"{skill_id} diff 无 lines")
    print(f"  [OK] GET diff {skill_id} v{nums[0]}→v{nums[-1]} lines={len(lines)}")
    dl_headers = {k: v for k, v in headers.items() if k.lower() != "content-type"}
    resp = requests.get(
        f"{GATEWAY_URL}/api/skills/{skill_id}/versions/{nums[0]}/download",
        headers=dl_headers,
        timeout=60,
    )
    resp.raise_for_status()
    if not resp.content.startswith(b"PK"):
        raise RuntimeError(f"{skill_id} download 非 zip")
    with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
        names = zf.namelist()
    if "SKILL.md" not in names:
        raise RuntimeError(f"{skill_id} zip 缺少 SKILL.md")
    print(f"  [OK] GET download {skill_id} zip entries={len(names)}")


def check_meta_roundtrip(headers: dict) -> None:
    skill_id = f"verify-ui-{uuid.uuid4().hex[:8]}"
    api_json(
        "POST",
        "/api/skills",
        headers,
        json={"id": skill_id, "displayName": "UI验", "description": "temp"},
    )
    skill_md = f"---\nname: {skill_id}\ndescription: temp\n---\n\n# {skill_id}\n"
    upload_headers = {k: v for k, v in headers.items() if k.lower() != "content-type"}
    up = requests.post(
        f"{GATEWAY_URL}/api/skills/{skill_id}/upload",
        headers=upload_headers,
        files={"file": ("SKILL.md", skill_md.encode("utf-8"), "text/markdown")},
        timeout=60,
    )
    up.raise_for_status()
    unwrap_r(up.json(), context="upload")
    new_name = f"UI验-{datetime.now():%H%M%S}"
    api_json(
        "PUT",
        f"/api/skills/{skill_id}",
        headers,
        json={"displayName": new_name, "description": "updated"},
    )
    row = next(s for s in api_json("GET", "/api/skills", headers) if s.get("id") == skill_id)
    if row.get("displayName") != new_name:
        raise RuntimeError("PUT 元数据未生效")
    print(f"  [OK] POST/PUT/UPLOAD 元数据 roundtrip skillId={skill_id}")
    api_json("DELETE", f"/api/skills/{skill_id}", headers)
    print(f"  [OK] DELETE {skill_id}")


def main() -> int:
    parser = argparse.ArgumentParser(description="3.12 /skills 管理页 API Live 验收")
    parser.add_argument("--skip-mutating", action="store_true", help="跳过创建/上传/删除临时 Skill")
    args = parser.parse_args()

    print(f"=== 3.12 Skills UI Live === Gateway={GATEWAY_URL}")
    try:
        requests.get(f"{GATEWAY_URL}/api/auth/login", timeout=5)
    except requests.RequestException as exc:
        print(f"[FAIL] Gateway 不可达: {exc}", file=sys.stderr)
        return 1

    headers = auth_headers()
    print("[1/4] 列表 + Catalog index")
    check_list_and_catalog(headers)

    print(f"[2/4] 读链路 ({SAMPLE_SKILL})")
    check_read_skill(headers, SAMPLE_SKILL)

    print(f"[3/4] diff + 下载 ({DIFF_SKILL})")
    check_diff_and_download(headers, DIFF_SKILL)

    if not args.skip_mutating:
        print("[4/4] 元数据 + 上传 roundtrip（临时 Skill）")
        check_meta_roundtrip(headers)
    else:
        print("[4/4] skip mutating")

    print("[PASS] 3.12 /skills 管理页 API Live")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
