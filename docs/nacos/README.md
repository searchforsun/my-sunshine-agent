# Nacos 配置（唯一配置源）

业务配置**只**维护在本目录，与线上 Nacos 保持一致。各服务 `application.yml` 仅负责 `import nacos:{dataId}.yaml`，**无** `application-dev.yaml`。

## 配置清单

| Data ID | 模块 |
|---------|------|
| `sunshine-gateway.yaml` | API Gateway |
| `sunshine-auth.yaml` | Auth Center |
| `sunshine-bff.yaml` | BFF |
| `sunshine-orchestrator.yaml` | Orchestrator |
| `sunshine-workflows.yaml` | Orchestrator Workflow 目录与 DAG |
| `sunshine-llm-gateway.yaml` | LLM Gateway |
| `sunshine-rag.yaml` | RAG Service |
| `sunshine-finance.yaml` | Finance Service |
| `sunshine-tool-manager.yaml` | Tool Manager |
| `sunshine-desensitize.yaml` | Desensitize |
| `sunshine-prompt.yaml` | Prompt Manager |

Group：`DEFAULT_GROUP`，格式：YAML。

## 变更流程

1. **只改** `docs/nacos/{dataId}.yaml`
2. 同步到线上：

```bash
python scripts/sync_nacos.py
# 或单文件：
python scripts/sync_nacos.py --data-id sunshine-gateway.yaml
```

3. **重启**受影响的服务（Nacos 动态刷新对多数 Spring 配置不自动生效）

## 启动服务

```bash
python scripts/start.py
# 或
java -jar target/sunshine-xxx.jar
```

无需 `--spring.profiles.active=dev`。需保证 `ecs4c16g:8848` Nacos 可达且配置已上传。

## CORS / SSE（Gateway）

| 项 | 说明 |
|----|------|
| `globalcors` | 浏览器跨域（含 SSE） |
| `DedupeResponseHeader` | 去重下游误带的 CORS 头 |
| `DevCorsWebFilter` | 代码层仅处理 `OPTIONS` 预检 |
| BFF | **勿**配置 `CorsWebFilter` |

## 上传方式（备选）

Nacos Console：http://ecs4c16g:8848/nacos（nacos/nacos）→ 配置管理 → 粘贴同名文件内容。
