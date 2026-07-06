#!/bin/sh
# MinIO 启动包装：compose 挂载 ./minio/init；业务 bucket 由 rag/skill-manager ensureBucket 创建
set -e
exec minio server /data --console-address ":9001"
