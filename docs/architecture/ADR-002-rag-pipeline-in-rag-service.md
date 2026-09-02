# ADR-002：RAG 检索链路内聚 rag-service

| 项 | 值 |
|----|-----|
| **状态** | Accepted |
| **日期** | 2026-07-01 |
| **范围** | 阶段四 4.1 前置 · RAG 检索 / 改写 / rerank 职责边界 |
| **关联** | [rag-knowledge-studio-design.md](../superpowers/specs/archive/2026-06-27-rag-knowledge-studio-design.md) · [phase4-platformization-design.md](../superpowers/specs/phase4-platformization-design.md) §4.1 |

---

## 背景

阶段三落地后，RAG 完整链路被拆在两个服务：

| 能力 | 当前归属 |
|------|----------|
| vector / BM25 / RRF / rerank | `rag-service` `RetrievalService` |
| rag / HyDE / empty-recall 改写 + 多轮 fallback | `orchestrator` `KnowledgeRetrievalService` + `QueryRewriteService` |
| 检索策略 `hybrid+rerank` | orchestrator 传参覆盖 rag-service 默认 |

后果：

1. 一次用户检索可能触发 **1～N 次** `POST /api/rag/search`（empty-recall 并行 alt query）。
2. 改写 prompt 在 `sunshine-orchestrator.yaml`，检索参数在 `sunshine-rag.yaml`，运营 publish / 评测无法单域闭环。
3. `scripts/rag_eval.py` 在 Python 复刻 orchestrator 改写逻辑，与 Chat 路径易漂移。
4. 阶段四工作台 debug / eval 若再移植改写，出现第三套实现。

---

## 决策

### 1. rag-service 拥有完整「语义检索 pipeline」

在 `rag-service` 内新增 **`KnowledgeRetrievalPipeline`**（名称锁定），内聚：

```
rag 改写 → 首次检索(vector/hybrid/rerank) → [0 命中] HyDE → 再检索 → [仍 0] empty-recall → 合并 → 返回
```

`RetrievalService` 仍为单次检索引擎；pipeline 负责编排与 fallback。

### 2. 对外提供「干净检索接口」

**主入口**：`POST /api/rag/search`（演进，非破坏性扩展）

| 字段 | 说明 |
|------|------|
| `query` | 用户原始问题（必填） |
| `topK` | 返回条数 |
| `tenantId` / Header `x-tenant-id` | 租户 |
| `kbId` | 知识库（可选，默认 tenant 默认库） |
| `strategy` | 可选，默认读 effective config |
| `options.rewrite` | 默认 `true`；`false` 时跳过改写链（admin debug 用） |
| `options.includeTrace` | 默认 `false`；Chat/workflow 传 `true` 供 Timeline |

**响应**（扩展）：

| 字段 | 说明 |
|------|------|
| `results[]` | `{ docName, content, score }` |
| `effectiveQuery` | 首次检索实际使用的 query |
| `trace.stages[]` | rewrite / hyde / vector / bm25 / rrf / rerank / filter / empty-recall |
| `trace.searchCount` | 内部检索调用次数 |

Admin debug：`POST /api/rag/admin/search/debug` 与 pipeline 共用同一实现，强制 `includeTrace=true`。

### 3. orchestrator 只负责「调用检索工具」

| 保留在 orchestrator | 迁出到 rag-service |
|----------------------|-------------------|
| `agent.rewrite.intent`（L3 意图路由） | `agent.rewrite.rag` |
| `agent.rewrite.planner`（Plan 规划前） | `agent.rewrite.rag.hyde` |
| `QueryRewriteService` 上述两类 | `agent.rewrite.empty-recall` |
| `RagTool` / `RagNodeHandler` 调用 `RagClient` | `KnowledgeRetrievalService` 多轮编排 |
| Timeline：解析 response `trace` 写入 step metadata | `QueryRewriteTrace` 记录 RAG 改写 |

`orchestrator` 删除或瘦身为：`RagClient.searchKnowledge(query, topK, tenantId, kbId, includeTrace)` → 单次 HTTP。

`orchestrator` 的 `rag.search.default-top-k` / `strategy` **废弃**，改由 rag-service effective config 或请求体 `topK`/`strategy` 决定。

### 4. 配置 SSOT 迁移

| 配置 | 迁移后 dataId |
|------|---------------|
| `rag.rewrite.rag` | `sunshine-rag.yaml` |
| `rag.rewrite.hyde` | 同上 |
| `rag.rewrite.empty-recall` | 同上 |
| `rag.rewrite.timeline.*`（步骤文案） | 同上或 `agent.timeline` 只读引用 |
| `rag.search.*` / `rag.rerank.*` | 已在此 |
| `agent.rewrite.intent` / `planner` | 保留 `sunshine-orchestrator.yaml` |

迁移期：`docs/nacos/sunshine-orchestrator.yaml` 已删除 `agent.rewrite.rag/hyde/empty-recall`（2026-07-01 pipeline 上线）。

### 5. 评测与脚本

- `EvaluateService` / `scripts/rag_eval.py` **只调** rag-service pipeline，删除 Python/Java 侧 duplicate 改写。
- publish 硬门禁 smoke eval 覆盖 **全 pipeline**（含改写），非仅 vector/rerank。

---

## 后果

### 正面

- Chat / Workflow / ReAct / 评测 / 工作台 **同源 pipeline**，Recall 可复现。
- 配置、debug 瀑布、A/B、publish 单域闭环。
- orchestrator 与 rag-service RPC 从 N 次降为 **1 次**。

### 负面 / 代价

- rag-service 新增对 llm-gateway 依赖（改写 flash 模型）。
- 需迁移 orchestrator 单测与 Timeline trace 组装逻辑。
- 阶段三文档中「KnowledgeRetrievalService 链」描述为**历史实现**，以本 ADR 为准。

---

## 实施顺序（锁定）

1. **4.0** rag-service pipeline + 扩展 `/api/rag/search` + Nacos 新键
2. orchestrator `RagClient` 切换 + 删除 RAG 改写编排 + trace 透传
3. `rag_eval.py` / CI 对齐
4. 4.1 工作台（catalog / debug / eval / publish）在 pipeline 之上建设

---

## 相关文档

- [2026-06-27-rag-knowledge-studio-design.md](../superpowers/specs/archive/2026-06-27-rag-knowledge-studio-design.md) §2、§6、§8
- [2026-06-27-rag-knowledge-studio.md](../superpowers/plans/2026-06-27-rag-knowledge-studio.md) Task T0
