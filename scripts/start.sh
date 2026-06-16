#!/usr/bin/env bash
# Start core Sunshine services (llm-gateway → orchestrator → bff → gateway)
# Optional SkyWalking agent when docker/skywalking-agent/skywalking-agent.jar exists
# Usage: bash scripts/start.sh [--profile dev]

set -euo pipefail

PROFILE="${1:-dev}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKY_AGENT="$ROOT/docker/skywalking-agent/skywalking-agent.jar"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PIDS=()

cleanup() {
  echo ""
  echo "Stopping services ..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

skywalking_opts() {
  local service="$1"
  if [[ -f "$SKY_AGENT" ]]; then
    echo "-javaagent:${SKY_AGENT} -DSW_AGENT_NAME=sunshine-${service} -DSW_AGENT_COLLECTOR_BACKEND_SERVICES=ecs4c16g:11800"
  fi
}

find_jar() {
  local module="$1"
  local artifact="$2"
  local jar
  jar="$(ls -1 "$ROOT/$module/target/${artifact}-"*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)"
  if [[ -z "$jar" ]]; then
    echo "[FAIL] JAR not found: $module/target/${artifact}-*.jar — run: mvn package -DskipTests" >&2
    exit 1
  fi
  echo "$jar"
}

start_service() {
  local name="$1"
  local module="$2"
  local artifact="$3"
  local jar
  jar="$(find_jar "$module" "$artifact")"
  local opts
  opts="$(skywalking_opts "$name")"
  echo "Starting sunshine-${name} ($jar) profile=${PROFILE} ..."
  if [[ -n "$opts" ]]; then
    echo "  SkyWalking agent enabled"
  fi
  # shellcheck disable=SC2086
  nohup "$JAVA_BIN" $opts -jar "$jar" --spring.profiles.active="$PROFILE" \
    > "$ROOT/$module/logs/startup.log" 2>&1 &
  PIDS+=("$!")
  sleep 3
}

mkdir -p "$ROOT/llm-gateway/logs" "$ROOT/orchestrator/logs" "$ROOT/bff/logs" "$ROOT/gateway/logs"

if [[ ! -f "$SKY_AGENT" ]]; then
  echo "[INFO] SkyWalking agent not found — run: bash scripts/download-skywalking-agent.sh"
fi

start_service "llm-gateway" "llm-gateway" "sunshine-llm-gateway"
start_service "orchestrator" "orchestrator" "sunshine-orchestrator"
start_service "bff" "bff" "sunshine-bff"
start_service "gateway" "gateway" "sunshine-gateway"

echo ""
echo "[OK] Core services started (Ctrl+C to stop)"
echo "  LLM Gateway  :8300"
echo "  Orchestrator :8200"
echo "  BFF          :8001"
echo "  Gateway      :8000"
echo "Live SkyWalking trace requires OAP at ecs4c16g:11800"

wait
