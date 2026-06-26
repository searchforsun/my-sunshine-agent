#!/bin/sh
# ============================================
# MinIO 容器入口包装脚本
# 1. 启动 MinIO 服务（后台）
# 2. 等待 MinIO 就绪
# 3. 执行初始化脚本（创建 bucket、上传图片）
# 4. 将 MinIO 切回前台运行
# ============================================
set -e

MINIO_ENTRYPOINT="/usr/bin/docker-entrypoint.sh"
INIT_SCRIPT="/minio-init/init-minio.sh"
MINIO_PID_FILE="/tmp/minio.pid"

echo "=== MinIO Wrapper: 启动 MinIO 服务 ==="

# 以原 entrypoint 方式启动 MinIO（后台）
"$MINIO_ENTRYPOINT" server /data --console-address ":9001" &
MINIO_PID=$!
echo "$MINIO_PID" > "$MINIO_PID_FILE"

# 等待 MinIO 就绪
echo "=== MinIO Wrapper: 等待 MinIO 就绪 ==="
for i in $(seq 1 30); do
  if curl -sf "http://localhost:9000/minio/health/live" > /dev/null 2>&1; then
    echo "MinIO 已就绪"
    break
  fi
  sleep 2
done

# 执行初始化
if [ -f "$INIT_SCRIPT" ]; then
  echo "=== MinIO Wrapper: 执行初始化脚本 ==="
  sh "$INIT_SCRIPT"
else
  echo "=== MinIO Wrapper: 初始化脚本不存在，跳过 ($INIT_SCRIPT) ==="
fi

echo "=== MinIO Wrapper: 初始化完成，MinIO 前台运行 ==="

# 将 MinIO 切回前台
wait "$MINIO_PID"
