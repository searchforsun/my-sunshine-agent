# RAG 可观测（Task 3.4.6 / 3.5）— **指标与面板 JSON 已实现**

## 本地开发（方案 B — 默认）

中间件与 Prometheus/Grafana 在 **ecs4c16g**，`sunshine-rag` 在 **本机 :8400** 启动时：

- 远程 Prometheus **无法**抓取本机 `localhost:8400`（无隧道则不可达）
- **不依赖 Grafana**；检索质量与延迟以 `rag_eval.py` 为准
- Micrometer 指标在本机 `/actuator/prometheus` 自查即可

### 日常验收

```bash
set RAG_URL=http://localhost:8400
pip install -r scripts/requirements.txt

# 检索质量门禁（主验收）
python scripts/rag_eval.py --suite v5 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98
python scripts/rag_eval.py --suite v6 --strategy vector --tag local-v6-vector
python scripts/rag_eval.py --suite v6 --strategy hybrid+rerank --ci ^
  --compare-vector-json docs/rag/reports/baseline-*-local-v6-vector.json

# 门禁逻辑单测（无需 RAG）
python scripts/test_rag_eval_gates.py -v
```

### 本地指标抽查

```bash
# 健康检查
curl http://localhost:8400/actuator/health

# 发起几次检索后再看 rag_* 指标
curl -X POST http://localhost:8400/api/rag/search ^
  -H "Content-Type: application/json" ^
  -d "{\"query\":\"请假几天\",\"topK\":5,\"strategy\":\"hybrid+rerank\"}"

curl http://localhost:8400/actuator/prometheus | findstr rag_
```

PowerShell：

```powershell
Invoke-RestMethod http://localhost:8400/actuator/health
(Invoke-WebRequest http://localhost:8400/actuator/prometheus).Content -split "`n" | Select-String "^rag_"
```

---

## 指标端点

```
GET http://localhost:8400/actuator/prometheus
```

Nacos `sunshine-rag.yaml`：`management.endpoints.web.exposure.include: health,info,prometheus`。

## 指标清单

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `rag_search_requests_total` | Counter | strategy, status | 检索请求数 |
| `rag_search_duration_seconds` | Timer | strategy, status | 端到端耗时 |
| `rag_search_hits` | Summary | strategy | 有效命中条数 |
| `rag_empty_total` | Counter | strategy | 空召回次数 |
| `rag_search_errors_total` | Counter | strategy | 检索异常 |
| `rag_rerank_duration_seconds` | Timer | status | Rerank API 耗时 |
| `rag_rerank_errors_total` | Counter | — | Rerank 失败 |
| `rag_vector_anchor_empty_total` | Counter | — | 向量锚点拦截 |

---

## 服务器 Grafana（可选，Task 3.5）

仅当 **rag-service 与 Prometheus 同机（ecs4c16g）** 时再配置抓取：

```yaml
  - job_name: sunshine-rag
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets: ['host.docker.internal:8400']   # 或宿主机内网 IP:8400
        labels:
          application: sunshine-rag
```

Grafana（ecs4c16g:3000）→ Import `rag-dashboard.json`；告警见 `rag-alerts.yml`。

本地开发阶段可跳过；上线联调或需要长期看板时再启用。
