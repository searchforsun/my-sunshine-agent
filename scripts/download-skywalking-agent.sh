#!/usr/bin/env bash
# Download SkyWalking Java Agent 9.7.0 to docker/skywalking-agent/
# Usage: bash scripts/download-skywalking-agent.sh

set -euo pipefail

VERSION="9.7.0"
URL="https://dlcdn.apache.org/skywalking/java-agent/${VERSION}/apache-skywalking-java-agent-${VERSION}.tgz"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$ROOT/docker/skywalking-agent"
JAR_PATH="$DEST_DIR/skywalking-agent.jar"
TMP_TGZ="$(mktemp /tmp/skywalking-agent.XXXXXX.tgz)"
TMP_EXTRACT="$(mktemp -d /tmp/skywalking-agent.XXXXXX)"

mkdir -p "$DEST_DIR"

echo "Downloading SkyWalking Java Agent ${VERSION} ..."
curl -fsSL -o "$TMP_TGZ" "$URL"

echo "Extracting ..."
tar -xzf "$TMP_TGZ" -C "$TMP_EXTRACT"

EXTRACTED_JAR="$(find "$TMP_EXTRACT" -name 'skywalking-agent.jar' | head -n 1)"
if [[ -z "$EXTRACTED_JAR" || ! -f "$EXTRACTED_JAR" ]]; then
  echo "[FAIL] skywalking-agent.jar not found in archive" >&2
  exit 1
fi

cp "$EXTRACTED_JAR" "$JAR_PATH"

rm -rf "$TMP_TGZ" "$TMP_EXTRACT"

echo "[OK] $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"
echo "Live trace requires OAP at ecs4c16g:11800 (see docker/skywalking-agent/README.md)"
