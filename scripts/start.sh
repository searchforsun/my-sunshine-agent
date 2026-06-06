#!/bin/bash
# Sunshine AI Platform — 服务启动脚本
# 用法: ./scripts/start.sh [service-name]   (不传参则启动全部)
set -e

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
SW_AGENT="$PROJ/docker/skywalking-agent/skywalking-agent.jar"
SW_OAP="8.140.48.6:11800"
JAVA="java"

# JDK 21 优先
[ -f "$JAVA_HOME/bin/java" ] && JAVA="$JAVA_HOME/bin/java"

# SkyWalking 探针
SW_OPTS=""
if [ -f "$SW_AGENT" ]; then
  SW_OPTS="-javaagent:$SW_AGENT -DSW_AGENT_COLLECTOR_BACKEND_SERVICES=$SW_OAP"
  echo "[SkyWalking] Agent ready"
else
  echo "[SkyWalking] Agent not found — download from https://skywalking.apache.org/downloads/"
fi

mkdir -p "$PROJ/logs"

declare -A SVC=(
  ["gateway"]="gateway|8000"
  ["bff"]="bff|8001"
  ["auth"]="auth-center|8100"
  ["orchestrator"]="orchestrator|8200"
  ["tool"]="tool-manager|8210"
  ["llm"]="llm-gateway|8300"
  ["rag"]="rag-service|8400"
  ["prompt"]="prompt-manager|8500"
  ["desensitize"]="desensitize|8600"
  ["oa"]="oa-service|8700"
  ["finance"]="finance-service|8710"
)

start_one() {
  local name="$1" info="${SVC[$name]}"
  if [ -z "$info" ]; then echo "未知服务: $name (可用: ${!SVC[*]})"; return 1; fi
  local mod="${info%%|*}" port="${info##*|}"
  echo "=== sunshine-$name :$port ==="
  cd "$PROJ/$mod"
  nohup $JAVA $SW_OPTS -DSW_AGENT_NAME="sunshine-$name" -Dserver.port="$port" \
    -jar target/sunshine-*.jar > "$PROJ/logs/$name.log" 2>&1 &
  echo "  PID: $! | log: logs/$name.log"
}

case "${1:-all}" in
  all) for s in "${!SVC[@]}"; do start_one "$s"; sleep 1; done; echo "=== All started ===" ;;
  *)   start_one "$1" ;;
esac
