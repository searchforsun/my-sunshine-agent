# Nacos 配置上传

## 配置清单

| Data ID | 模块 | 说明 |
|---------|------|------|
| `sunshine-llm-gateway.yaml` | LLM Gateway | Redis、LLM 厂商配置 |
| `sunshine-orchestrator.yaml` | Orchestrator | Agent Prompt、模型配置 |
| `sunshine-bff.yaml` | BFF | Orchestrator 地址 |
| `sunshine-rag.yaml` | RAG Service | Milvus、Embedding 配置 |

均为 `DEFAULT_GROUP`，YAML 格式。

## 上传方式

### 方式 1：Nacos Console（推荐）

1. 打开 http://8.140.48.6:8848/nacos（nacos/nacos）
2. 配置管理 → 配置列表 → 新建配置（或导入）
3. 逐个创建上述 4 个配置，内容见同目录下 `.yaml` 文件

### 方式 2：API 批量上传

```bash
NACOS="http://8.140.48.6:8848/nacos"
AUTH="username=nacos&password=nacos"

for f in sunshine-llm-gateway.yaml sunshine-orchestrator.yaml sunshine-bff.yaml sunshine-rag.yaml; do
  curl -X POST "$NACOS/v1/cs/configs" \
    -d "$AUTH" \
    --data-urlencode "dataId=$f" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=$(cat $f)"
  echo " uploaded: $f"
done
```

### 方式 3：使用 Python 脚本

```bash
pip install nacos-sdk-python  # 或直接 curl
python upload_nacos_configs.py
```

## 本地开发

本地开发无需 Nacos，使用 `application-dev.yaml`：

```bash
java -jar target/sunshine-xxx.jar --spring.profiles.active=dev
```

`application-dev.yaml` 包含与 Nacos 配置相同的本地默认值。
