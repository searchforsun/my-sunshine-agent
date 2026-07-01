# RAG 知识库工作台 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `/knowledge` 升级为阶段四一站式知识库运营工作台（**4.0 pipeline 前置** + 4.1 + 4.2 做全），管理 API 全部内聚 `rag-service :8400`，含多 kb、参数 publish、debug 瀑布、评测闭环、OCR 入库、Chat kb 选择器。

**Architecture:** [ADR-002](../../architecture/ADR-002-rag-pipeline-in-rag-service.md) — `KnowledgeRetrievalPipeline` 内聚改写+检索+rerank+HyDE+empty-recall；orchestrator 只调 `RagClient.searchKnowledge()`。rag-service 新增 MySQL `sunshine_rag` + Milvus/ES schema 扩展；tenant 检索+改写参数经 Nacos Open API publish；前端 `KnowledgeView` 重构为 Skills 式工作台。

**Tech Stack:** JDK 21 · Spring Boot 3.2 · JPA + Flyway · Milvus · ES · Redis · Vue3/Naive UI · DashScope OCR · Nacos Open API · llm-gateway

**设计 SSOT:** [2026-06-27-rag-knowledge-studio-design.md](../specs/2026-06-27-rag-knowledge-studio-design.md) · [ADR-002](../../architecture/ADR-002-rag-pipeline-in-rag-service.md)

**非目标:** 新建 rag-manager 微服务 · Chat `#kb` · 4.3 L2 全量 · RBAC 细分

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **Pipeline（4.0）** | `pipeline/KnowledgeRetrievalPipeline.java`、`pipeline/QueryRewritePipeline.java`、`client/LlmGatewayClient.java` | `RetrievalController.java`、`docs/nacos/sunshine-rag.yaml`（`rag.rewrite.*`） | `KnowledgeRetrievalPipelineTest.java` |
| **Infra** | `rag-service/.../db/migration/V1__rag_schema.sql` | `rag-service/pom.xml`、`docs/nacos/sunshine-rag.yaml` | — |
| **Entity/Repo** | `entity/*.java`、`repository/*.java` | — | `KnowledgeBaseRepositoryTest.java` |
| **Catalog** | `admin/KbAdminController.java`、`catalog/*Service.java` | `MilvusService.java`、`ElasticsearchIndexService.java` | `KbAdminControllerTest.java`、`TenantIsolationIntegrationTest.java` |
| **Config** | `config/RagChunkProperties.java`、`admin/config/*` | `MarkdownParser.java`、`RagSearchProperties.java` | `EffectiveConfigServiceTest.java`、`NacosPublishServiceTest.java` |
| **Debug** | `admin/debug/RetrievalDebugService.java` | `RetrievalService.java`、`HybridRetrievalService.java` | `RetrievalDebugServiceTest.java` |
| **Eval** | `admin/eval/*` | — | `EvaluateServiceTest.java` |
| **Ingest** | `admin/ingest/*`、`parser/DocxParser.java`、`ocr/DashScopeOcrService.java` | `IngestionController.java` | `IngestJobStateMachineTest.java` |
| **Orchestrator** | — | `RagClient.java`（扩展 trace）；**删除** `KnowledgeRetrievalService` RAG 编排；Timeline trace 透传；Chat DTO | `RagClientTest.java` |
| **Frontend** | `sunshine-ui/src/api/ragAdmin.ts`、`components/knowledge/*` | `KnowledgeView.vue`、`ChatView.vue` | `npx vue-tsc -b` |
| **Ops** | `scripts/rag_reindex.py`、`scripts/verify_rag_studio.py` | `scripts/rag_eval.py`（去掉 Python 改写，只调 pipeline） | 手测 + CI smoke |

---

## 迭代排期

```
迭代 0（P0 Pipeline） T0 → T0b → T0c → T0d   检索链路内聚 rag-service + orchestrator 瘦身
迭代 1（P0 底座）     T1 → T2 → T3              MySQL + admin 鉴权 + Milvus/ES schema
迭代 2（P0 Catalog）   T4 → T5 → T6              kb/doc/version API + 检索 kb 过滤
迭代 3（P0 Debug+UI）  T7 → T8 → T9              debug 瀑布 + 工作台壳 + 文档/调试 Tab
迭代 4（P0 Config）    T10 → T11 → T12          参数 schema/draft + Nacos publish + 硬门禁
迭代 5（P0 Eval）      T13 → T14 → T15          EvaluateService + Badcase + 评测 Tab
迭代 6（P1 Ingest）    T16 → T17 → T18          多格式 + OCR + quarantine + 入库 Tab
迭代 7（P1 Chat）      T19 → T20                kb 选择器 + orchestrator kbId
迭代 8（P2 收尾）      T21 → T22                A/B + 周报 Cron + reindex + 全量验收
```

---

## Task T0: KnowledgeRetrievalPipeline + 扩展 `/api/rag/search`

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/pipeline/KnowledgeRetrievalPipeline.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/pipeline/RetrievalTrace.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/controller/RetrievalController.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/pipeline/KnowledgeRetrievalPipelineTest.java`

**Step 1:** Pipeline 编排（port `orchestrator/.../KnowledgeRetrievalService` 逻辑）：

```
rag改写 → RetrievalService.search → [0命中] HyDE → search → [仍0] empty-recall → merge
```

**Step 2:** 扩展 `POST /api/rag/search` request/response（spec §2.1）：`options.rewrite`、`options.includeTrace`、`kbId`（可选）、response `effectiveQuery` + `trace`。

**Step 3:** 单测 mock `RetrievalService` + rewrite：0 命中触发 hyde；hyde 仍 0 触发 empty-recall merge。

```bash
mvn test -pl rag-service -Dtest=KnowledgeRetrievalPipelineTest
```

Expected: PASS

---

## Task T0b: QueryRewritePipeline + Nacos 迁移

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/pipeline/QueryRewritePipeline.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/config/RagRewriteProperties.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/client/LlmGatewayClient.java`
- Modify: `docs/nacos/sunshine-rag.yaml`（新增 `rag.rewrite.rag/hyde/empty-recall`，自 orchestrator 复制 prompt）
- Modify: `docs/nacos/sunshine-orchestrator.yaml`（旧键注释 `@deprecated`，迁移完成后删除）

**Step 1:** Port `QueryRewriteService` 中 **rag / hyde / empty-recall** 三类（不含 intent/planner）。

**Step 2:** `RagRewriteProperties` 绑定 `rag.rewrite.*`；timeline 文案键 `rag.rewrite.timeline.*`。

**Step 3:**

```bash
python scripts/sync_nacos.py --data-id sunshine-rag.yaml
mvn test -pl rag-service -Dtest=QueryRewritePipelineTest
```

Expected: PASS

---

## Task T0c: orchestrator 瘦身为 RagClient

- [x]

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/client/RagClient.java`
- Delete or deprecate: `orchestrator/.../rag/KnowledgeRetrievalService.java`
- Modify: `orchestrator/.../agent/RagTool.java`、`execution/handler/RagNodeHandler.java`
- Modify: `orchestrator/.../processing/TimelineSessionCompletions.java`（从 response trace 组装 RAG rewrite detail）
- Delete: orchestrator `QueryRewriteService` 中 rag/hyde/empty-recall 方法（保留 intent/planner）

**Step 1:** `RagClient.searchKnowledge(query, topK, tenantId, kbId, includeTrace)` → 单次 `POST /api/rag/search`。

**Step 2:** 删除 orchestrator 内多轮 `ragClient.search` 编排；`RagTool` / `RagNodeHandler` 改调新方法。

**Step 3:** Timeline：`trace.stages` 中 rewrite/hyde/empty-recall → step metadata（替代 `QueryRewriteTrace` RAG 场景）。

```bash
mvn test -pl orchestrator -Dtest=RagClientTest,RagNodeHandlerTest
python scripts/phase2_agent_demo.py --suite react
```

Expected: PASS；live knowledge-qa workflow 仍绿

---

## Task T0d: rag_eval.py / CI 对齐 pipeline

- [x]

**Files:**
- Modify: `scripts/rag_eval.py`（删除 `load_rag_rewrite_cfg` / Python 改写；只 POST rag-service）
- Modify: `.github/workflows/rag-eval.yml`（如需要）

**Step 1:** eval 脚本不再读 `sunshine-orchestrator.yaml` 做改写；默认 `options.rewrite=true`。

**Step 2:** 对比迁移前后 v5 Recall@5 偏差 < 0.01：

```bash
python scripts/rag_eval.py --suite v5 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98
```

Expected: PASS（与迁移前 baseline 对齐）

---

## Task T1: rag-service MySQL + Flyway

- [x]

**Files:**
- Modify: `rag-service/pom.xml`
- Modify: `docs/nacos/sunshine-rag.yaml`
- Create: `rag-service/src/main/resources/db/migration/V1__rag_schema.sql`
- Create: `rag-service/src/main/java/com/sunshine/rag/entity/KnowledgeBaseEntity.java`（及 spec §5.1 其余 entity）
- Create: `rag-service/src/main/java/com/sunshine/rag/repository/KnowledgeBaseRepository.java`（及对应 repository）

**Step 1:** `pom.xml` 增加（对齐 skill-manager）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

**Step 2:** `docs/nacos/sunshine-rag.yaml` 追加：

```yaml
spring:
  datasource:
    url: jdbc:mysql://ecs4c16g:3306/sunshine_rag?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    username: root
    password: root123
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
```

**Step 3:** `V1__rag_schema.sql` 建表（spec §5.1 全表）；`knowledge_base` 唯一键 `(tenant_id, kb_id)`；种子数据：tenant `default` 下 kb `default` 且 `is_default=1`。

**Step 4:** Entity 用 `@Entity` + `@Table`；`KbConfigOverrideEntity.overrideJson` 用 `@Column(columnDefinition = "JSON")` 或 `TEXT` + Jackson 转换。

**Step 5:** 本地建库并编译：

```bash
# MySQL SSOT：docker/mysql/init/01-init-databases.sql + 09-sunshine-rag.sql
mvn compile -pl rag-service -am
python scripts/sync_nacos.py --data-id sunshine-rag.yaml
```

Expected: BUILD SUCCESS；Flyway V1 applied

---

## Task T2: Admin 鉴权与包结构

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/AdminTokenFilter.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/config/AdminWebConfig.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/config/RagAdminProperties.java`

**Step 1:** `AdminTokenFilter` 拦截 `/api/rag/admin/**`（`/api/rag/admin/rebuild` 已有 token 校验，统一到 Filter）：

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminTokenFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/api/rag/admin/")) {
            chain.doFilter(req, res);
            return;
        }
        String token = req.getHeader("X-Admin-Token");
        String required = adminProperties.getToken();
        if (required != null && !required.isBlank() && !required.equals(token)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.getWriter().write("{\"code\":401,\"msg\":\"admin token invalid\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
```

**Step 2:** 创建包：`com.sunshine.rag.admin.catalog`、`admin.config`、`admin.debug`、`admin.eval`、`admin.ingest`。

**Step 3:** 单测：

```bash
mvn test -pl rag-service -Dtest=RagAdminControllerTest
```

Expected: PASS（扩展现有 rebuild 测试覆盖 401）

---

## Task T3: Milvus / ES schema 演进

- [x]

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/ElasticsearchIndexService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/VectorSearchService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/Bm25SearchService.java`
- Modify: `rag-service/src/test/java/com/sunshine/rag/service/TenantIsolationIntegrationTest.java`

**Step 1:** Milvus collection 字段新增：`kb_id`(VarChar 64)、`doc_id`(VarChar 128)、`version`(Int32)、`chunk_index`(Int32)、`status`(VarChar 16)、`source_type`(VarChar 32)。`schemaSupportsTenant()` 改为 `schemaSupportsV2()` 检测上述字段；不匹配则 rebuild（与现有 tenant migration 同模式）。

**Step 2:** `insert` 签名扩展：

```java
public void insert(ChunkInsertRequest req) {
    // doc_name, content, embedding, tenant_id, kb_id, doc_id, version, chunk_index, status, source_type
}
```

旧 `IngestionController` 调用时默认 `kbId=default`、`docId=docName`、`version=1`、`status=active`、`sourceType=markdown`。

**Step 3:** `search` expr 扩展：

```java
String expr = String.format(
    "tenant_id == \"%s\" && kb_id == \"%s\" && status == \"active\"",
    escape(tid), escape(kbId));
```

**Step 4:** ES mapping 追加同名字段；`indexChunk` 写入；BM25 query 加 `term` filter `kb_id` + `status`。

**Step 5:**

```bash
mvn test -pl rag-service -Dtest=TenantIsolationIntegrationTest,TenantSearchQueryTest
```

Expected: PASS；集成测试覆盖两 kb 隔离

---

## Task T4: 知识库 CRUD API

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/KnowledgeBaseService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/KbAdminController.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/admin/catalog/KbAdminControllerTest.java`

**Step 1:** API 实现 spec §6.1：

| Method | Path | 行为 |
|--------|------|------|
| GET | `/api/rag/admin/kbs` | `@RequestHeader x-tenant-id` 过滤 |
| POST | `/api/rag/admin/kbs` | body: `{kbId, displayName, description?}` |
| PUT | `/api/rag/admin/kbs/{kbId}/default` | 同 tenant 仅一个 `is_default=1` |

**Step 2:** 单测 MockMvc：`POST` 创建 → `GET` 列表含新 kb → `PUT default` 切换。

**Step 3:**

```bash
mvn test -pl rag-service -Dtest=KbAdminControllerTest
```

Expected: BUILD SUCCESS

---

## Task T5: 文档与版本 API

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentCatalogService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/KbDocumentAdminController.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/controller/IngestionController.java`（委托 catalog ingest，保留旧路径兼容）

**Step 1:** `POST /api/rag/admin/kbs/{kbId}/ingest/text`：

- 创建/更新 `document` + 新 `document_version`
- 旧 version `status=superseded`；Milvus/ES 旧 chunk 标 `superseded`（按 `doc_id+version` filter update 或 delete+reinsert）
- 调用现有 embed 流水线写入新 chunk

**Step 2:** `GET documents`、`GET documents/{docId}`、`GET chunks?version=` 从 MySQL + Milvus query 组装。

**Step 3:** `DELETE .../versions/{version}` 仅标 superseded，不物理删 MySQL 行。

**Step 4:** 单测：v1 ingest → v2 ingest → search 仅命中 v2。

```bash
mvn test -pl rag-service -Dtest=DocumentCatalogServiceTest
```

Expected: PASS

---

## Task T6: EffectiveConfigService + 检索 kbId

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/config/EffectiveConfigService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/config/KbConfigOverrideService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/RetrievalService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/controller/RetrievalController.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/admin/config/EffectiveConfigServiceTest.java`

**Step 1:** `EffectiveConfigService.resolve(tenantId, kbId)` 返回合并对象：

```java
public record EffectiveRagConfig(
    float minScore,
    String strategy,
    int rrfK,
    int hybridPoolSize,
    float rerankMinScore,
    int chunkMaxSize
) {}
```

Nacos `@ConfigurationProperties` 为 tenant 默认；MySQL `kb_config_override.override_json` 稀疏覆盖。

**Step 2:** `RetrievalController.search` body 增加可选 `kbId`（默认 `default`）；传入 `RetrievalService.search(..., kbId, effectiveConfig)`。

**Step 3:** `PUT/DELETE /api/rag/admin/kbs/{kbId}/config/override` 实现 spec §6.3。

**Step 4:**

```bash
mvn test -pl rag-service -Dtest=EffectiveConfigServiceTest,HybridRetrievalServiceTest
```

Expected: PASS

---

## Task T7: 检索 debug 瀑布 API

- [x]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/debug/RetrievalDebugService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/debug/RetrievalDebugController.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/pipeline/KnowledgeRetrievalPipeline.java`（暴露 debug 中间态）
- Create: `rag-service/src/test/java/com/sunshine/rag/admin/debug/RetrievalDebugServiceTest.java`

**Step 1:** `RetrievalDebugService` **委托** `KnowledgeRetrievalPipeline`（强制 `includeTrace=true`），不 duplicate 改写逻辑。

```java
Mono<RetrievalTrace> debugSearch(String query, int topK, EffectiveRagConfig cfg, String tenantId, String kbId);
```

各 stage 由 pipeline 收集，不截断 candidate 内容。

**Step 2:** `POST /api/rag/admin/search/debug` 响应：

```json
{
  "stages": [
    {"name":"vector","candidates":[...],"latencyMs":12},
    {"name":"bm25","candidates":[...],"latencyMs":8},
    {"name":"rrf","candidates":[...],"latencyMs":1},
    {"name":"rerank","candidates":[...],"latencyMs":120},
    {"name":"filter","dropped":[...],"latencyMs":0}
  ],
  "final": [...]
}
```

`includeRewrite=true` 时等价 `options.rewrite=true`（默认）；rewrite stages 来自 pipeline 内部。

**Step 3:** 单测 mock vector/bm25 返回固定候选，断言 stage 顺序与 source 字段。

```bash
mvn test -pl rag-service -Dtest=RetrievalDebugServiceTest
```

Expected: PASS

---

## Task T8: 前端 API 层 + 工作台壳

- [x]

**Files:**
- Create: `sunshine-ui/src/api/ragAdmin.ts`
- Create: `sunshine-ui/src/components/knowledge/KbLayout.vue`
- Create: `sunshine-ui/src/components/knowledge/KbSidebar.vue`
- Modify: `sunshine-ui/src/views/KnowledgeView.vue`
- Modify: `sunshine-ui/src/api/knowledge.ts`（deprecated 注释，转发 ragAdmin）

**Step 1:** `ragAdmin.ts`：

```typescript
const API_BASE = import.meta.env.VITE_RAG_API_BASE ?? 'http://localhost:8400'
const ADMIN_TOKEN = import.meta.env.VITE_RAG_ADMIN_TOKEN ?? 'sunshine-rag-admin-dev'

function adminHeaders(tenantId: string): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    'x-tenant-id': tenantId,
    'X-Admin-Token': ADMIN_TOKEN,
  }
}

export async function listKbs(tenantId: string): Promise<KnowledgeBase[]>
export async function listDocuments(tenantId: string, kbId: string): Promise<KbDocument[]>
export async function debugSearch(tenantId: string, kbId: string, body: DebugSearchRequest): Promise<DebugSearchResponse>
```

**Step 2:** `KnowledgeView.vue` 重构为 Skills 式布局：顶栏 tenant + kb 下拉；左栏 kb 列表 + 文档树；右栏 `NTabs`。

**Step 3:**

```bash
cd sunshine-ui && npx vue-tsc -b
```

Expected: 无 TS 错误

---

## Task T9: 文档 Tab + 检索调试 Tab

- [x]

**Files:**
- Create: `sunshine-ui/src/components/knowledge/KbDocPanel.vue`
- Create: `sunshine-ui/src/components/knowledge/KbDebugPanel.vue`
- Create: `sunshine-ui/src/components/knowledge/RetrievalWaterfall.vue`

**Step 1:** `KbDocPanel`：文档列表 → 选中 → 版本下拉 → chunk 列表（content 全文展示，不截断）。

**Step 2:** `KbDebugPanel`：query 输入 + strategy override 可选 + 「调试检索」；`RetrievalWaterfall` 按 stage 折叠展示 candidate（docName、score、source Tag）。

**Step 3:** 手测：上传 md → 文档 Tab 见 chunk → 调试 Tab 见 vector/bm25/rrf/rerank 分数。

---

## Task T10: 参数 schema + 草稿 API

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/config/RagConfigSchemaService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/config/ConfigDraftService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/config/KbConfigAdminController.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/config/RagChunkProperties.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/parser/MarkdownParser.java`
- Modify: `docs/nacos/sunshine-rag.yaml`（新增 `rag.chunk.max-size: 1200`）

**Step 1:** `RagConfigSchemaService.getSchema()` 返回 Catalog（fieldId、label、type、min/max、scope、currentValue）；**禁止硬编码默认值到前端**，schema 即 SSOT。

**Step 2:** `ConfigDraftService` CRUD `config_draft` 表；scope 枚举见 spec §6.3。

**Step 3:** `MarkdownParser` 注入 `RagChunkProperties.getMaxSize()` 替代 `MAX_CHUNK_SIZE` 常量。

**Step 4:**

```bash
mvn test -pl rag-service -Dtest=RagConfigSchemaServiceTest,MarkdownParserTest
```

Expected: PASS

---

## Task T11: NacosPublishService

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/config/NacosPublishService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/config/RagNacosProperties.java`
- Modify: `docs/nacos/sunshine-rag.yaml`（`rag.nacos.server-addr/username/password/group`）
- Create: `rag-service/src/test/java/com/sunshine/rag/admin/config/NacosPublishServiceTest.java`

**Step 1:** `NacosPublishService.publish(scope, payloadJson)`：

1. 根据 scope 映射 dataId + yaml path（如 `rag-search` → `sunshine-rag.yaml` → `rag.search`）
2. `WebClient GET /v1/cs/configs?dataId=&group=&tenant=`
3. SnakeYAML load → patch 节点 → dump
4. `POST /v1/cs/configs`（同 `scripts/sync_nacos.py`）
5. 可选：写回 `docs/nacos/{dataId}` 工作区副本

**Step 2:** 单测用 WireMock 模拟 Nacos：输入 sample yaml + patch min-score → 断言 POST body 含新值。

```bash
mvn test -pl rag-service -Dtest=NacosPublishServiceTest
```

Expected: PASS

---

## Task T12: 发布硬门禁 + 参数 Tab UI

- [ ]

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/config/KbConfigAdminController.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/EvaluateService.java`（最小 smoke 版，T13 扩展）
- Create: `sunshine-ui/src/components/knowledge/KbConfigPanel.vue`

**Step 1:** `POST .../config/drafts/{scope}/publish`：

```
1. 读取 draft payload
2. 运行 smoke eval（golden-set 前 50 条，当前 draft 作为 overrides）
3. recall_at_5 >= baseline（MySQL 最近 passed_gate 报告，无则 0.98）
4. 通过 → NacosPublishService.publish + draft.status=published + 写 eval_report
5. 失败 → HTTP 422 + failedSamples + 调 suggest（T15）
```

**Step 2:** `KbConfigPanel`：切换「租户默认 / 当前 kb」；表单绑定 schema；「保存草稿」「发布」；发布失败展示门禁原因。

**Step 3:** 手测：故意改高 min-score → publish 被拒 → 恢复 → publish 成功。

---

## Task T13: EvaluateService 全量

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/EvaluateService.java`（扩展）
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/EvalAdminController.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/GoldenSetLoader.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/admin/eval/EvaluateServiceTest.java`

**Step 1:** 移植 `scripts/rag_eval.py` 核心到 **`KnowledgeRetrievalPipeline`**（非 duplicate 改写）：

- `recall_at_k`、`mrr`
- rewrite / hyde / pipeline 路径
- 报告 JSON + markdown（格式对齐 `docs/rag/reports/`）

**Step 2:** API：

| Method | Path |
|--------|------|
| POST | `/api/rag/admin/eval/run` |
| GET | `/api/rag/admin/eval/jobs/{jobId}` |
| GET | `/api/rag/admin/eval/reports/{reportId}` |

异步：`@Async` + Redis job 状态或 DB `eval_job.status`。

**Step 3:** 对齐测试：同 suite 与 `rag_eval.py` 偏差 < 0.01（mock 检索固定 hits）。

```bash
mvn test -pl rag-service -Dtest=EvaluateServiceTest
python scripts/rag_eval.py --suite v5 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98
```

Expected: 两者均 PASS（live 环境）

---

## Task T14: Badcase + 评测 Tab UI

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/BadcaseAdminController.java`
- Create: `sunshine-ui/src/components/knowledge/KbEvalPanel.vue`
- Create: `sunshine-ui/src/components/knowledge/KbBadcasePanel.vue`

**Step 1:** Badcase CRUD + `POST export-golden` 输出 YAML（格式对齐 `golden-set.yaml` queries 段）。

**Step 2:** `KbEvalPanel`：选择 suite、kb、跑评测、进度条、报告表格（Recall@5/MRR/Δ）、历史列表。

**Step 3:** `KbBadcasePanel`：从 debug 失败样本「加入 Badcase」；标注 relevant docIds。

---

## Task T15: 优化建议 + A/B

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/SuggestService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/eval/EvalAdminController.java`

**Step 1:** `POST /api/rag/admin/eval/suggest`：输入低分样本 + config snapshot → llm-gateway flash → 结构化 JSON suggestions。

**Step 2:** `POST /api/rag/admin/eval/ab`：并行跑 current vs draft config，返回并排 metrics。

**Step 3:** UI「应用为草稿」按钮：将 suggestion.target 写入对应 scope draft。

---

## Task T16: 多格式 ingest + 状态机

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/ingest/IngestJobService.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/ingest/KbIngestAdminController.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/ingest/IngestStateMachine.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/admin/ingest/IngestStateMachineTest.java`

**Step 1:** 状态机：`parsing → preview → quarantine? → embedding → active | failed`。

**Step 2:** `POST ingest/file` multipart：`MimeTypeDetector` 分支 md/txt/docx/pdf/png/jpeg。

**Step 3:** `GET ingest-jobs/{id}`、`POST confirm`、`POST reject`。

**Step 4:** 单测覆盖全转移；非法转移抛 `BizException`。

```bash
mvn test -pl rag-service -Dtest=IngestStateMachineTest
```

Expected: PASS

---

## Task T17: docx + PDF 文本层 + DashScope OCR

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/parser/DocxParser.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/ocr/PdfTextExtractor.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/ocr/DashScopeOcrService.java`
- Modify: `rag-service/pom.xml`（apache poi、pdfbox 等）

**Step 1:** docx → Markdown（标题/段落/表格）。

**Step 2:** PDF：pdfbox 抽文本层；空或乱码 → `DashScopeOcrService.ocr(bytes, mimeType)`。

**Step 3:** 图片 → OCR → Markdown；计算 `confidence`；低于阈值 → `quarantine`，否则 `preview`（单文件默认）。

**Step 4:** 产出统一走 `MarkdownParser` → embed；`source_type` 写入 Milvus。

---

## Task T18: 脱敏 + 入库 Tab UI

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/ingest/DesensitizeClient.java`
- Create: `sunshine-ui/src/components/knowledge/KbIngestPanel.vue`

**Step 1:** confirm 前调 `desensitize :8600`（与阶段三一致）；失败阻断 embed。

**Step 2:** `KbIngestPanel`：拖拽上传；子 Tab「进行中 / 待审核 / 已完成」；preview Markdown 编辑器（只读或可编辑后 confirm）；批量勾选 `autoPassHighConfidence`。

**Step 3:** 手测：PDF 上传 → preview → confirm → 5 分钟内 debug 可检索。

---

## Task T19: Chat kb 选择器（前端 + orchestrator）

- [ ]

**Files:**
- Modify: `sunshine-ui/src/views/ChatView.vue`
- Create: `sunshine-ui/src/components/chat/KbSelector.vue`
- Modify: `sunshine-ui/src/stores/chatSessions.ts`（或等价 session store，存 `kbId`）
- Modify: `orchestrator/.../client/RagClient.java`（T0c 已扩展 `kbId` + `includeTrace`）
- Modify: Chat 请求 DTO（`ChatCompletionRequest` 或等价）增加 `kbId`

**Step 1:** `KbSelector.vue`：挂载时 `listKbs(tenantId)`；默认选中 `is_default` kb；变更写入 session。

**Step 2:** Chat SSE 请求 body 增加 `kbId`。

**Step 3:** `RagClient.searchKnowledge(..., kbId, includeTrace=true)`；orchestrator **不再**本地编排改写。

**Step 4:**

```bash
mvn test -pl orchestrator -Dtest=RagClientTest
cd sunshine-ui && npx vue-tsc -b
```

Expected: PASS

---

## Task T20: orchestrator 默认 kb 解析

- [ ]

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/client/RagKbClient.java`（或 RagClient 扩展 `GET /api/rag/admin/kbs` 轻量 public 端点）
- Modify: `orchestrator/.../agent/RagTool.java`、`execution/handler/RagNodeHandler.java`

**Step 1:** 若请求无 `kbId`：调 rag-service 解析 tenant 默认 kb（可缓存 60s）；fallback `default`。

**Step 2:** Workflow `RagNodeHandler` / plan rag 节点：`params.kbId` 可选，继承 session kbId。

**Step 3:** Live：Chat 切换 kb → 问「年假」→ 调试 Tab 同 query 同 kb 结果一致。

---

## Task T21: 评测周报 Cron + rag_reindex

- [ ]

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/eval/EvalWeeklyScheduler.java`
- Create: `scripts/rag_reindex.py`
- Modify: `scripts/rag_eval.py`（`--kb-id` 可选）

**Step 1:** `@Scheduled(cron = "0 0 2 * * MON")` 跑全 tenant 默认 kb v5 suite；报告落盘 + `eval_report` 索引。

**Step 2:** `rag_reindex.py`：按 kb 全量 re-embed；进度 stdout；对接 `POST /api/rag/admin/rebuild` 仅灾难恢复。

**Step 3:**

```bash
python scripts/rag_reindex.py --kb-id default --dry-run
```

Expected: 输出待重建 chunk 数

---

## Task T22: 全量验收脚本 + 文档

- [ ]

**Files:**
- Create: `scripts/verify_rag_studio.py`
- Modify: `docs/superpowers/specs/2026-06-27-rag-knowledge-studio-design.md`（状态 → 实施中）
- Modify: `CLAUDE.md`（追加 rag studio 命令）

**Step 1:** `verify_rag_studio.py` 自动检查 spec §9 检查门：

| # | 检查 |
|---|------|
| 1 | 创建 kb + ingest text + search 命中 |
| 2 | v2 supersede v1 |
| 3 | debug 返回 ≥4 stages |
| 4 | publish 门禁（mock 低分被拒） |
| 5 | eval smoke Recall@5 |
| 6 | Chat kbId 字段透传（可选 curl orchestrator） |

**Step 2:**

```bash
python scripts/verify_rag_studio.py
mvn test -pl rag-service
cd sunshine-ui && npx vue-tsc -b
```

Expected: 全绿

---

## Spec 覆盖自检

| Spec 章节 | Task |
|-----------|------|
| §0 需求决策 11 项 | 全 plan |
| §2.1 干净检索 API / ADR-002 | T0–T0d |
| §3 页面 6 Tab | T8–T9、T12、T14、T18 |
| §5 数据模型 | T1、T3 |
| §6.1 catalog API | T4–T5 |
| §6.2 ingest | T16–T18 |
| §6.3 config | T6、T10–T12 |
| §6.4 Nacos publish | T11–T12 |
| §6.5 debug | T7、T9 |
| §6.6 eval | T12–T15、T21 |
| §6.7 badcase | T14 |
| §7 Chat kb | T19–T20 |
| §9 检查门 | T22 |

---

## 执行选项

**Plan complete and saved to `docs/superpowers/plans/2026-06-27-rag-knowledge-studio.md`. Two execution options:**

1. **Subagent-Driven（推荐）** — 每 Task 派生子 agent；**迭代 0（T0–T0d）必须先于工作台 UI**
2. **Inline Execution** — 本会话按 T0→T22 批量执行；**T0d eval 全绿** 为迭代 1 checkpoint

**Which approach?**
