#!/bin/sh
# ============================================
# MinIO 初始化脚本（容器内运行）
# 创建 bucket、设置公开访问、上传示例商品图片
#
# 由 entrypoint-wrapper.sh 在 MinIO 启动后自动调用
# 也可手动执行: docker exec smt-minio sh /minio-init/init-minio.sh
# ============================================
set -e

SCRIPT_DIR="/minio-init"
IMAGES_DIR="${SCRIPT_DIR}/images"
BUCKET="smt-product"
ALIAS="local"
MINIO_ENDPOINT="http://localhost:9000"
MINIO_USER="${MINIO_ROOT_USER:-minioadmin}"
MINIO_PASS="${MINIO_ROOT_PASSWORD:-minioadmin123}"

echo "=== MinIO Init: 开始初始化 ==="

# 1. 等待 MinIO 就绪（由 wrapper 保证，此处做二次确认）
echo "[1/4] 确认 MinIO 就绪..."
for i in $(seq 1 10); do
  if curl -sf "$MINIO_ENDPOINT/minio/health/live" > /dev/null 2>&1; then
    echo "  MinIO 已就绪"
    break
  fi
  sleep 1
done

# 2. 配置 mc 别名
echo "[2/4] 配置 MinIO Client..."
mc alias set "$ALIAS" "$MINIO_ENDPOINT" "$MINIO_USER" "$MINIO_PASS"

# 3. 创建 bucket 并设为公开
echo "[3/4] 创建 bucket: $BUCKET"
mc mb --ignore-existing "$ALIAS/$BUCKET"
mc anonymous set public "$ALIAS/$BUCKET"
echo "  Bucket $BUCKET 已创建并设为公开访问"

# 4. 上传图片
echo "[4/4] 上传商品图片..."
if [ ! -d "$IMAGES_DIR" ]; then
  echo "  警告: 图片目录不存在: $IMAGES_DIR"
  exit 0
fi

uploaded=0
for img in "$IMAGES_DIR"/*.jpg "$IMAGES_DIR"/*.png; do
  [ -f "$img" ] || continue
  fname=$(basename "$img")
  mc cp "$img" "$ALIAS/$BUCKET/$fname"
  echo "  已上传: $fname"
  uploaded=$((uploaded + 1))
done

# 输出信息
HOST_IP="${DOCKER_HOST_IP:-localhost}"
API_PORT="${MINIO_API_PORT:-9000}"
CONSOLE_PORT="${MINIO_CONSOLE_PORT:-9001}"

echo ""
echo "=== MinIO Init: 完成 ($uploaded 张图片) ==="
echo "图片 URL 示例: http://${HOST_IP}:${API_PORT}/${BUCKET}/milk.jpg"
echo "MinIO 控制台:   http://${HOST_IP}:${CONSOLE_PORT}"
echo "  用户名: $MINIO_USER"
echo "  密码:   $MINIO_PASS"
