#!/usr/bin/env python3
"""聊天图片输入 live 验收：上传 → 契约 → 下发 → 多模态能力校验（spec §6）。

口径说明：
- 上传/发消息走 gateway :8000 全链路（临时注册账号登录，鉴权链路与前端一致）；
- 测试图片由代码生成（zlib+struct 手工封装 PNG），保证字节确定且为上游可解析的合法图片；
- SSE 内容断言宽松（模型生成不可控），llm-gateway 收到的图片交付形式
  由 delivery-mode 决定（base64 → data:image/...;base64, / url → MinIO 地址），
  按 logs/sunshine-llm-gateway.log 人工核对（脚本末尾给出 grep 口径）；
- 多模态拒绝：直调 llm-gateway /v1/chat/completions 断言 400 + 友好文案 + 原始 code，
  orchestrator 层另验非多模态模型带图发消息 → SSE error 帧（失败透传不悬挂）。
"""
import io
import struct
import sys
import uuid
import zlib

import requests

GATEWAY = "http://localhost:8000"
ORCH = "http://localhost:8200"
LLM_GW = "http://localhost:8000/v1/chat/completions"

OK, FAIL = [], []


def check(name, cond, detail=""):
    (OK if cond else FAIL).append(f"{name} {detail}")
    print(("✅" if cond else "❌"), name, detail)


def png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + chunk_type + data
            + struct.pack(">I", zlib.crc32(chunk_type + data) & 0xFFFFFFFF))


def png_bytes() -> bytes:
    """1x8 蓝紫渐变 PNG：zlib+struct 手工封装，确定性输出且确为合法图片"""
    ihdr = struct.pack(">IIBBBBB", 8, 1, 8, 2, 0, 0, 0)
    raw = b"".join(b"\x00" + bytes([40, r, 255 - r]) for r in range(8))
    return (b"\x89PNG\r\n\x1a\n"
            + png_chunk(b"IHDR", ihdr)
            + png_chunk(b"IDAT", zlib.compress(raw))
            + png_chunk(b"IEND", b""))


def auth_headers() -> dict:
    """注册临时账号并登录，返回带 Bearer 的请求头（与前端同链路经 gateway 鉴权）"""
    suffix = uuid.uuid4().hex[:8]
    username = f"chat_image_{suffix}"
    password = "password123"
    requests.post(f"{GATEWAY}/api/auth/register",
                  json={"username": username, "password": password}, timeout=15)
    login = requests.post(f"{GATEWAY}/api/auth/login",
                          json={"username": username, "password": password}, timeout=15)
    login.raise_for_status()
    token = (login.json().get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {login.text[:200]}")
    return {"Authorization": f"Bearer {token}"}


def upload(headers: dict, filename: str, content: bytes, content_type: str):
    return requests.post(
        f"{GATEWAY}/api/chat/images",
        headers=headers,
        files={"file": (filename, io.BytesIO(content), content_type)},
        timeout=15)


def read_sse_data_lines(resp) -> list:
    texts = []
    for raw in resp.iter_lines():
        if not raw:
            continue
        line = raw.decode("utf-8", "ignore")
        if line.startswith("data:"):
            texts.append(line[5:].strip())
    return texts


def verify_upload(headers: dict) -> str:
    png = png_bytes()
    r = upload(headers, "px.png", png, "image/png")
    check("上传 200", r.status_code == 200, r.text[:120])
    url = ""
    try:
        url = (r.json().get("data") or {}).get("url") or ""
    except ValueError:
        pass
    check("返回 URL", url.startswith("http"), url)
    if url:
        pulled = requests.get(url, timeout=10)
        check("URL 可拉取且字节一致",
              pulled.status_code == 200 and pulled.content == png, str(pulled.status_code))
    bad = upload(headers, "a.exe", b"MZ", "application/octet-stream")
    check("非图片类型 400", bad.status_code == 400, str(bad.status_code))
    return url


def send_message(url: str, model: str) -> list:
    payload = {
        "conversationId": "",
        "content": "图里是什么颜色",
        "imageUrls": [url],
        "executionMode": "fast",
        "modelName": model,
    }
    with requests.post(f"{ORCH}/chat/stream", json=payload,
                       headers={"x-user-id": "verify", "x-tenant-id": "default"},
                       stream=True, timeout=90) as resp:
        if resp.status_code != 200:
            return []
        return read_sse_data_lines(resp)


def verify_chat_happy_path(url: str):
    """多模态模型带图发消息：SSE 应有正文帧（主备模型带回退，规避单上游波动）"""
    for model in ("glm-5.3-flash", "MiniMax-M3"):
        data_lines = send_message(url, model)
        has_content = any('"type":"content' in t for t in data_lines)
        check(f"多模态模型带图 → SSE 正文帧（model={model}）", has_content,
              f"{len(data_lines)} 帧")
        if has_content:
            return


def verify_multimodal_reject_at_gateway(url: str):
    """非多模态模型直调 llm-gateway：400 + 友好文案（含模型名）+ 原始 code"""
    body = {
        "model": "qwen-plus",
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": "图里是什么"},
                {"type": "image_url", "image_url": {"url": url}},
            ],
        }],
    }
    r = requests.post(LLM_GW, json=body, timeout=30)
    check("gateway 多模态拒绝 400", r.status_code == 400, str(r.status_code))
    err = {}
    try:
        err = r.json().get("error") or {}
    except ValueError:
        pass
    message = err.get("message") or ""
    check("拒绝文案含模型名",
          "当前模型不支持图片输入" in message and "qwen-plus" in message, message[:80])
    check("原始 code 保留", err.get("code") == "model_not_multimodal", str(err.get("code")))


def verify_orch_error_frame(url: str):
    """非多模态模型经 orchestrator 带图发消息：SSE 应有 error 帧（失败透传，不悬挂）"""
    data_lines = send_message(url, "qwen-plus")
    check("非多模态模型带图 → SSE error 帧",
          any('"type":"error"' in t for t in data_lines), f"{len(data_lines)} 帧")


def main() -> int:
    headers = auth_headers()
    url = verify_upload(headers)
    if not url:
        print(f"\n通过 {len(OK)} 项，失败 {len(FAIL)} 项")
        return 1
    verify_chat_happy_path(url)
    verify_multimodal_reject_at_gateway(url)
    verify_orch_error_frame(url)
    # llm-gateway 收到的图片交付形式（data: 前缀=base64 模式 / sunshine-chat-images=url 模式）人工核对
    print('提示：grep -a "data:image\\|sunshine-chat-images" logs/sunshine-llm-gateway.log | tail -3')
    print(f"\n通过 {len(OK)} 项，失败 {len(FAIL)} 项")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
