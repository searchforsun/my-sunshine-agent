#!/usr/bin/env bash
# 阶段一检查门 — 联调演示脚本（Linux / Git Bash）
# 用法: bash scripts/phase1-demo.sh
# 前置: llm-gateway(:8300) orchestrator(:8200) bff(:8001) rag-service(:8400) 已启动

set -euo pipefail

LLM_URL="${LLM_GATEWAY_URL:-http://localhost:8300}"
BFF_URL="${BFF_URL:-http://localhost:8001}"
RAG_URL="${RAG_URL:-http://localhost:8400}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8000}"
USER_ID="${DEMO_USER_ID:-phase1-demo}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOC_PATH="$ROOT/docs/knowledge/公司请假流程规范.md"

step() { echo ""; echo "======== $1 ========"; }
ok()   { echo "[OK] $*"; }
fail() { echo "[FAIL] $*" >&2; }
warn() { echo "[WARN] $*" >&2; }

step "0. 服务探活"
for name_url in "LLM|$LLM_URL/actuator/health" "Orchestrator|http://localhost:8200/actuator/health" "BFF|$BFF_URL/actuator/health" "RAG|$RAG_URL/actuator/health"; do
  name="${name_url%%|*}"
  url="${name_url#*|}"
  if curl -sf --max-time 5 "$url" >/dev/null 2>&1; then
    ok "$name 可达: $url"
  else
    warn "$name 不可达: $url"
  fi
done

step "1. LLM Gateway → DeepSeek 调用"
if resp=$(curl -sf --max-time 60 -X POST "$LLM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"用一句话介绍你自己"}]}'); then
  echo "$resp" | head -c 200
  echo ""
  ok "DeepSeek 调用成功"
else
  fail "LLM Gateway 调用失败 — 请确认 DEEPSEEK_API_KEY 已配置"
fi

step "2. RAG 文档入库 + 检索"
if [[ ! -f "$DOC_PATH" ]]; then
  fail "示例文档不存在: $DOC_PATH"
else
  ingest_payload=$(python3 - <<PY
import json, pathlib
p = pathlib.Path("$DOC_PATH")
print(json.dumps({"docName": "公司请假流程规范", "content": p.read_text(encoding="utf-8")}, ensure_ascii=False))
PY
)
  if ingest=$(curl -sf --max-time 120 -X POST "$RAG_URL/api/rag/documents" \
    -H "Content-Type: application/json; charset=utf-8" \
    -d "$ingest_payload"); then
    ok "入库: $ingest"
  else
    fail "RAG 入库失败 — 请确认 Milvus + Embedding API Key"
  fi

  if search=$(curl -sf --max-time 30 -X POST "$RAG_URL/api/rag/search" \
    -H "Content-Type: application/json" \
    -d '{"query":"病假需要哪些材料","topK":3}'); then
    ok "检索: $(echo "$search" | head -c 200)..."
  else
    fail "RAG 检索失败"
  fi
fi

step "3. BFF SSE 流式（5 秒采样）"
timeout 5 curl -N -s -X POST "$BFF_URL/api/chat/stream" \
  -H "Content-Type: application/json" \
  -H "x-user-id: $USER_ID" \
  -H "x-tenant-id: default" \
  -d '{"content":"hello, introduce Sunshine AI in one sentence"}' | head -n 8 || true
ok "SSE 流已采样"

step "4. 知识库问答全链路（10 秒采样）"
timeout 10 curl -N -s -X POST "$BFF_URL/api/chat/stream" \
  -H "Content-Type: application/json" \
  -H "x-user-id: $USER_ID" \
  -H "x-tenant-id: default" \
  -d '{"content":"公司病假需要提交什么材料？"}' | head -n 12 || true
ok "知识库问答流已采样"

step "5. Gateway 转发"
if curl -sf --max-time 3 "$GATEWAY_URL/actuator/health" >/dev/null 2>&1 \
  || (command -v nc >/dev/null && nc -z localhost 8000 2>/dev/null); then
  if timeout 8 curl -N -s -X POST "$GATEWAY_URL/api/chat/stream" \
    -H "Content-Type: application/json" \
    -H "x-user-id: $USER_ID" \
    -H "x-tenant-id: default" \
    -d '{"content":"hello via gateway"}' | head -n 8; then
    ok "Gateway SSE 采样成功"
  else
    fail "Gateway 转发失败 — 请确认 Nacos 注册与 BFF/Orchestrator 已启动"
  fi
else
  warn "Gateway 端口 8000 未监听 [SKIP]"
fi

step "6. Nacos System Prompt 热更新（手动）"
cat <<'EOF'
[MANUAL] Nacos Console 修改 sunshine-orchestrator.yaml → agent.system-prompt，
         发布后立即对话验证，无需重启 Orchestrator。
  http://ecs4c16g:8848/nacos  (nacos/nacos)
EOF

step "7. 换浏览器恢复历史（手动）"
echo "[MANUAL] 两浏览器使用相同 x-user-id: $USER_ID 访问 /chat"

echo ""
ok "演示脚本执行完毕"
