# RAG 文档级多策略分块 Implementation Plan

> **状态**：✅ 已在分支实现（截至 `f5af3be` / 最新 HEAD）；Live 验收 `verify_chunk_strategies_live.py`。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 文档发布前强制预览并按文档选择分块策略（markdown / fixed / recursive / semantic / parent_child），去掉 KB 级分段配置，入库可复现、检索对父子块正确回填。

**Architecture:** `Chunker` 可插拔 + Redis `previewId` 门禁；`publish` / `ingestText` 只消费预览快照；`document_version` 落 strategy+params；Milvus/ES 增 metadata；检索命中 child 时回填 parent 文本。

**Tech Stack:** JDK 21 · Spring WebFlux · Spring Data Redis · JPA · Milvus · ES · Vue3/Naive UI · pytest 风格 Live 脚本（`scripts/*.py`）

**设计 SSOT:** [2026-07-21-rag-chunk-strategies-design.md](../specs/archive/2026-07-21-rag-chunk-strategies-design.md)

**非目标:** 独立入库 Tab · 策略 A/B 自动评测 · 预览写向量库 · 保留 KB `rag-chunk` 回退

---

## 文件结构（边界锁定）

| 区域 | 创建 | 修改 | 测试 |
|------|------|------|------|
| **Chunker 核心** | `rag-service/.../chunker/Chunker.java`、`ChunkDraft.java`、`ChunkStrategy.java`、`ChunkParams.java`、`ChunkerRegistry.java`、五种 `*Chunker.java` | 将 `MarkdownParser` 委托给 `MarkdownChunker` 或由其替换调用点 | `*ChunkerTest.java`、`ChunkerRegistryTest.java` |
| **Preview** | `.../chunker/ChunkPreviewService.java`、`ChunkPreviewStore.java`（Redis）、DTO | `KbDocumentAdminController.java`、`DocumentCatalogService.java` | `ChunkPreviewServiceTest.java`、`DocumentCatalogServiceTest.java` |
| **持久化** | — | `14-sunshine-rag-service.sql`、`DocumentVersionEntity.java`、config seed/schema（删 chunk） | `RagConfigSchemaServiceTest.java`、`ConfigBundle*` |
| **索引/检索** | — | `DocumentChunkIndexer.java`、`ChunkInsertRequest.java`、`MilvusService.java`、`ElasticsearchIndexService.java`、`RetrievalService`/`RetrievalCandidate` 父子回填 | 单测 + Live |
| **前端** | — | `kbDocuments.ts`、`KbDocPanel.vue`、`KbConfigPanel`/`useKbConfigPanel`/`kbConfigFieldHelp` | `npx vue-tsc -b`（sunshine-ui） |
| **运维** | `scripts/verify_chunk_strategies_live.py` | `scripts/rag_ingest_bulk.py`、`docs/rag/README.md`、`docs/rag/backlog.md` | Live |

---

## Task 1: Chunker 契约（接口 + 参数 + 草稿模型）

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkStrategy.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkParams.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkDraft.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/Chunker.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/ChunkParamsTest.java`

- [x] **Step 1: 写失败单测（非法 strategy / 缺参）**

```java
@Test
void parseStrategy_rejectsUnknown() {
    assertThatThrownBy(() -> ChunkStrategy.parse("foo"))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void chunkParams_fixedRequiresPositiveMaxSize() {
    assertThatThrownBy(() -> ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 0)))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [x] **Step 2: 实现契约**

```java
public enum ChunkStrategy {
    MARKDOWN("markdown"),
    FIXED("fixed"),
    RECURSIVE("recursive"),
    SEMANTIC("semantic"),
    PARENT_CHILD("parent_child");
    // parse(String): 大小写不敏感；未知抛 IllegalArgumentException
}

/** 不可变参数袋：按 strategy 校验并填默认值（见 spec §4 表） */
public record ChunkParams(
        int maxSize,
        int overlap,
        double similarityThreshold,
        int minChunkSize,
        int parentSize,
        int childSize,
        int childOverlap) {
    public static ChunkParams forStrategy(ChunkStrategy strategy, Map<String, Object> raw) { /* ... */ }
    public Map<String, Object> asMap() { /* 仅序列化该策略相关键 */ }
}

public record ChunkDraft(
        int index,
        String text,
        Map<String, Object> meta) {
    public int charCount() { return text == null ? 0 : text.length(); }
}

public interface Chunker {
    ChunkStrategy strategy();
    List<ChunkDraft> chunk(String markdown, ChunkParams params);
}
```

`meta` 约定（parent_child）：`level`=`parent`|`child`，`parentIndex`=父块 index（child 必有）。

- [x] **Step 3: 跑测**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn -pl rag-service -Dtest=ChunkParamsTest test
```

Expected: PASS

- [x] **Step 4: Commit**

```bash
git add rag-service/src/main/java/com/sunshine/rag/chunker/ \
  rag-service/src/test/java/com/sunshine/rag/chunker/ChunkParamsTest.java
git commit -m "feat(rag): add Chunker contract and ChunkParams defaults"
```

---

## Task 2: Fixed + Recursive Chunker

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/FixedLengthChunker.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/RecursiveChunker.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/FixedLengthChunkerTest.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/RecursiveChunkerTest.java`

- [x] **Step 1: 失败单测**

```java
@Test
void fixed_respectsMaxSizeAndOverlap() {
    String text = "甲".repeat(50) + "。乙".repeat(50) + "。";
    List<ChunkDraft> chunks = new FixedLengthChunker()
            .chunk(text, ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 40, "overlap", 10)));
    assertThat(chunks).isNotEmpty();
    assertThat(chunks.get(0).text().length()).isLessThanOrEqualTo(40);
}

@Test
void recursive_splitsOnBlankLinesFirst() {
    String text = "第一段内容足够长。\n\n第二段内容也足够长。\n\n第三段。";
    List<ChunkDraft> chunks = new RecursiveChunker()
            .chunk(text, ChunkParams.forStrategy(ChunkStrategy.RECURSIVE, Map.of("maxSize", 30, "overlap", 0)));
    assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
}
```

- [x] **Step 2: 实现**

- `FixedLengthChunker`：按 `maxSize` 窗口滑动，`overlap` 字符重叠；优先在窗口内最后句末标点 `[。！？.!?\n]` 处切开；无标点则硬切。
- `RecursiveChunker`：分隔符顺序 `"\n\n"`, `"\n"`, `"。"`, `""`；超长段递归再切；合并相邻碎片时应用 `overlap`（与 fixed 同类：下一块开头含上一块尾部 overlap 字符，或切点回退 overlap——实现选**切点回退**一种并在单测固定）。

- [x] **Step 3: 跑测 PASS → Commit**

```bash
mvn -pl rag-service -Dtest=FixedLengthChunkerTest,RecursiveChunkerTest test
git commit -m "feat(rag): add fixed and recursive chunkers"
```

---

## Task 3: MarkdownChunker（包装现有 MarkdownParser）

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/MarkdownChunker.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/parser/MarkdownParser.java`（保持 `parse(String,int)` 行为不变，供 Chunker 调用）
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/MarkdownChunkerTest.java`

- [x] **Step 1: 单测 — 与 MarkdownParser 输出一致**

```java
@Test
void markdownChunker_matchesParser() {
    MarkdownParser parser = new MarkdownParser(new RagChunkProperties());
    MarkdownChunker chunker = new MarkdownChunker(parser);
    String md = "# 标题\n\n段落一。\n\n## 小节\n\n段落二。";
    List<String> legacy = parser.parse(md, 1200);
    List<ChunkDraft> drafts = chunker.chunk(md, ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of()));
    assertThat(drafts.stream().map(ChunkDraft::text).toList()).isEqualTo(legacy);
}
```

- [x] **Step 2: 实现 `MarkdownChunker` 为 `@Component`，`strategy()=MARKDOWN`，调用 `parser.parse(md, params.maxSize())`，`meta=Map.of()`**

- [x] **Step 3: 跑测 PASS → Commit**

```bash
mvn -pl rag-service -Dtest=MarkdownChunkerTest,MarkdownParserTest test
git commit -m "feat(rag): wrap MarkdownParser as MarkdownChunker"
```

---

## Task 4: SemanticChunker

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/SemanticChunker.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/SemanticChunkerTest.java`

- [x] **Step 1: 单测（mock EmbeddingService）**

语义切点算法（固定契约）：

1. 按句末标点切成 sentences（空句丢弃）
2. 对每句 `embeddingService.embed(sentence).block()`（Chunker 层允许 block；PreviewService 在 boundedElastic 调用）
3. 相邻句余弦相似度；若 `sim < similarityThreshold` 且当前累积长度 ≥ `minChunkSize`，则在此切段
4. 段内拼接后若仍 > `maxSize`，再按 `FixedLengthChunker` 逻辑二次切（overlap=0）

```java
@Test
void semantic_splitsAtLowSimilarity() {
    EmbeddingService emb = mock(EmbeddingService.class);
    // 句0/1 高相似向量，句1/2 低相似
    when(emb.embed(anyString())).thenAnswer(inv -> { /* 返回可控向量 Mono */ });
    SemanticChunker chunker = new SemanticChunker(emb);
    List<ChunkDraft> out = chunker.chunk("句子甲。句子乙。完全无关的丙。",
            ChunkParams.forStrategy(ChunkStrategy.SEMANTIC,
                    Map.of("maxSize", 1200, "similarityThreshold", 0.5, "minChunkSize", 1)));
    assertThat(out.size()).isGreaterThanOrEqualTo(2);
}
```

- [x] **Step 2: 实现 → 跑测 PASS → Commit**

```bash
mvn -pl rag-service -Dtest=SemanticChunkerTest test
git commit -m "feat(rag): add embedding-boundary SemanticChunker"
```

---

## Task 5: ParentChildChunker

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ParentChildChunker.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/ParentChildChunkerTest.java`

- [x] **Step 1: 单测**

```java
@Test
void parentChild_emitsParentsAndChildrenWithLinks() {
    String text = "字".repeat(2500);
    List<ChunkDraft> drafts = new ParentChildChunker()
            .chunk(text, ChunkParams.forStrategy(ChunkStrategy.PARENT_CHILD,
                    Map.of("parentSize", 1000, "childSize", 200, "childOverlap", 20)));
    List<ChunkDraft> parents = drafts.stream()
            .filter(d -> "parent".equals(d.meta().get("level"))).toList();
    List<ChunkDraft> children = drafts.stream()
            .filter(d -> "child".equals(d.meta().get("level"))).toList();
    assertThat(parents).isNotEmpty();
    assertThat(children).isNotEmpty();
    assertThat(children.get(0).meta().get("parentIndex")).isInstanceOf(Integer.class);
}
```

- [x] **Step 2: 实现**

1. 先按 `parentSize`（定长、overlap=0）切父块，`meta.level=parent`
2. 每个父块内按 `childSize` + `childOverlap` 切子块，`meta.level=child`，`meta.parentIndex=父 index`
3. **返回列表顺序**：所有父块在前按其 index，再跟所有子块（或父1+其子…——**固定为：先全部 parent，再全部 child**，index 全局递增）
4. 入库侧（后续 Task）只对 `level=child` 做向量检索；parent 文本随 metadata 存储

- [x] **Step 3: 跑测 PASS → Commit**

```bash
mvn -pl rag-service -Dtest=ParentChildChunkerTest test
git commit -m "feat(rag): add ParentChildChunker"
```

---

## Task 6: ChunkerRegistry + 硬上限

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkerRegistry.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/ChunkerRegistryTest.java`

- [x] **Step 1–3: Registry 注入全部 `Chunker` bean；`chunk(strategy, md, params)`；结果 size>2000 抛业务异常（用现有 `BizException` + 新增 `RagErrorCode.CHUNK_LIMIT_EXCEEDED`）**

```java
@Component
@RequiredArgsConstructor
public class ChunkerRegistry {
    private final List<Chunker> chunkers;
    public List<ChunkDraft> chunk(ChunkStrategy strategy, String markdown, ChunkParams params) {
        Chunker chunker = chunkers.stream()
                .filter(c -> c.strategy() == strategy).findFirst()
                .orElseThrow(() -> new BizException(RagErrorCode.UNKNOWN_CHUNK_STRATEGY));
        List<ChunkDraft> drafts = chunker.chunk(markdown, params);
        if (drafts.size() > 2000) {
            throw new BizException(RagErrorCode.CHUNK_LIMIT_EXCEEDED);
        }
        // 重写 index 为 0..n-1 保证连续
        ...
    }
}
```

- [x] **Step 4: Commit** `feat(rag): register chunkers with 2000 chunk hard limit`

---

## Task 7: MySQL + Entity：version 记录 strategy/params

**Files:**
- Modify: `docker/mysql/init/14-sunshine-rag-service.sql`（文件末尾追加 ALTER）
- Modify: `rag-service/src/main/java/com/sunshine/rag/entity/DocumentVersionEntity.java`

- [x] **Step 1: SQL（幂等说明：已有库需手工执行同等 ALTER）**

```sql
USE sunshine_rag;
ALTER TABLE document_version
    ADD COLUMN chunk_strategy VARCHAR(32) NULL AFTER chunk_count,
    ADD COLUMN chunk_params_json JSON NULL AFTER chunk_strategy;
```

- [x] **Step 2: Entity 字段**

```java
@Column(name = "chunk_strategy", length = 32)
private String chunkStrategy;
@Column(name = "chunk_params_json", columnDefinition = "JSON")
private String chunkParamsJson;
```

- [x] **Step 3: 对运行中 MySQL 执行 ALTER（或文档注明 `mysql < alter`）；Commit**

```bash
git commit -m "feat(rag): persist chunk_strategy on document_version"
```

---

## Task 8: Redis ChunkPreviewService

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkPreviewRecord.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkPreviewService.java`
- Create: `rag-service/src/test/java/com/sunshine/rag/chunker/ChunkPreviewServiceTest.java`
- 确认 `docs/nacos/sunshine-rag.yaml` / `application.yml` 已有 Redis（`spring-boot-starter-data-redis` 已在 pom）；缺则补 host 配置

- [x] **Step 1: 单测用 Testcontainers 或嵌入式 mock `StringRedisTemplate`**

契约：

```java
public record ChunkPreviewRecord(
        String previewId,
        String tenantId,
        String kbId,
        String docId,
        String version,
        String contentHash,
        ChunkStrategy strategy,
        ChunkParams params,
        List<ChunkDraft> chunks,
        Instant expiresAt) {}

// createPreview(...): SHA-256(content) → chunk → Redis SET key=rag:chunk-preview:{id} TTL=30m
// requirePreview(tenant,kb,doc,previewId): 校验归属/未过期；不删
// consumePreview(...): require + DEL key，返回 record
```

`contentHash` 格式：`sha256:` + hex。

- [x] **Step 2: 实现（JSON 用现有 Jackson ObjectMapper）**

- [x] **Step 3: 跑测 PASS → Commit** `feat(rag): Redis-backed chunk preview with TTL`

---

## Task 9: Admin API — preview + publish 门禁

**Files:**
- Create: `rag-service/.../catalog/dto/ChunkPreviewRequest.java`
- Create: `rag-service/.../catalog/dto/ChunkPreviewResponse.java`
- Create: `rag-service/.../catalog/dto/PublishRequest.java`
- Modify: `KbDocumentAdminController.java`
- Modify: `DocumentCatalogService.java`
- Modify: `DocumentCatalogServiceTest.java`
- Modify: `IngestTextRequest.java`（增加 `strategy`、`params`；默认 `markdown`）

- [x] **Step 1: 失败单测**

```java
@Test
void publish_withoutPreviewId_fails() { ... }

@Test
void publish_whenContentChanged_returnsConflict() { ... }
```

- [x] **Step 2: Controller**

```java
@PostMapping("/documents/{docId}/chunk-preview")
public Mono<R<ChunkPreviewResponse>> chunkPreview(..., @RequestBody ChunkPreviewRequest body)

@PostMapping("/documents/{docId}/publish")
public Mono<R<IngestResult>> publishVersion(..., @RequestBody PublishRequest body)
// PublishRequest(String previewId) — version 从 preview 记录读取；删除旧的 ?version= query（若需兼容：query version 与 body 不一致则 400）
```

- [x] **Step 3: `DocumentCatalogService`**

- `chunkPreview(tenant,kb,doc,req)`：读 draft 内容（`version` 缺省当前 draft）→ desensitize 与 publish 相同 scrub → `ChunkParams.forStrategy` → `previewService.createPreview`
- `publishVersion(..., previewId)`：`consumePreview` → 校验 hash 与当前 draft scrub 后内容 → supersede → 写 version status/strategy/params/chunkCount → `chunkIndexer.embedAndIndexDrafts(...)`
- `ingestText`：解析 strategy（默认 markdown）→ `createPreview`（docId 可用临时）→ 立即 `consumePreview` 入库（脚本无 UI，仍走同一服务方法）

错误映射：`BizException` → 400；hash 不一致 → `RagErrorCode.PREVIEW_CONTENT_STALE` → **409**。

- [x] **Step 4: 跑测 PASS → Commit** `feat(rag): require chunk previewId to publish documents`

---

## Task 10: Indexer + Milvus/ES metadata（strategy / parent-child）

**Files:**
- Modify: `ChunkInsertRequest.java` — 扩展字段：`strategy`、`chunkLevel`、`parentChunkId`（可为 null）
- Modify: `DocumentChunkIndexer.java` — `embedAndIndexDrafts(...)`；parent_child：**仅 child 调 embed+insert 检索**；parent 写入 ES/Milvus 时 `chunk_level=parent` 且检索过滤排除，或 parent **只写 ES doc 旁路**——**本计划选定：parent 与 child 都写入 Milvus/ES，但检索 filter `chunk_level != parent` 或 `chunk_level = child OR chunk_level 不存在`（兼容旧数据）**
- Modify: `MilvusService.java` / `MemoryMilvusService.java` / `ElasticsearchIndexService.java` — schema 增加 `strategy`(varchar)、`chunk_level`(varchar)、`parent_chunk_id`(varchar)；旧 collection 沿用现有「schema 过旧则重建」逻辑
- parent_chunk_id 格式：`{docId}#v{version}#{parentIndex}`

- [x] **Step 1: 扩展 insert；更新 indexer 循环 `ChunkDraft`**

- [x] **Step 2: 单测 MemoryMilvus 或 indexer mock → Commit** `feat(rag): index chunk strategy and parent-child metadata`

---

## Task 11: 检索父子回填

**Files:**
- Modify: `RetrievalCandidate`（或等价模型）增加 `chunkLevel`、`parentChunkId`、`chunkId`
- Modify: vector/BM25 映射填充新字段
- Modify: `RetrievalService` 在返回 `DocFragment` 前：若命中 `chunk_level=child` 且 `parentChunkId` 非空，则按 id 取父 content，**用父文本替换/作为 DocFragment.content**（debug 可把 child 放进 trace；线上 content=父）
- Create: `rag-service/src/test/java/com/sunshine/rag/service/ParentChildRetrievalTest.java`

- [x] **Step 1: 单测 mock 命中 child → 断言返回父文本**

- [x] **Step 2: 实现 → Commit** `feat(rag): expand parent context for parent_child hits`

---

## Task 12: 移除 KB 级 `rag-chunk` 配置

**Files:**
- Modify: `ConfigScope.java` — 删除 `RAG_CHUNK`
- Modify: `RagConfigSchemaService.java`、`ConfigDraftMerger.java`、`ConfigBundlePayload.java`、`EffectiveRagConfig.java`、`ResolvedKbConfig.java`
- Modify: `config-seed.json`、`14-sunshine-rag-service.sql` 中 seed `payload_json`（去掉 `"chunk":{...}`）
- Modify: 所有读 `chunkMaxSize` 的测试夹具（`ConfigBundleTestFixtures` 等）
- Modify: Eval Suggest 可调路径（去掉 `chunk.maxSize`）
- `EffectiveRagConfig` 去掉最后一参 `chunkMaxSize`；`ResolvedKbConfig` 去掉 `chunkMaxSize`
- `KnowledgeRetrievalPipeline.searchWithConfig` 构造 `ResolvedKbConfig` 时同步改签名

- [x] **Step 1: 改 payload 解析：不再 `requireMap(payload,"chunk")`**

- [x] **Step 2: 全量相关单测**

```bash
mvn -pl rag-service -Dtest=RagConfigSchemaServiceTest,ConfigBundlePayload*,DocumentCatalogServiceTest test
```

- [x] **Step 3: Commit** `refactor(rag): remove KB-level rag-chunk config`

---

## Task 13: 前端 API + KbDocPanel 预览门禁 UI

**Files:**
- Modify: `sunshine-ui/src/api/ragAdmin/kbDocuments.ts`
- Modify: `sunshine-ui/src/components/knowledge/KbDocPanel.vue`
- 可选 Create: `sunshine-ui/src/components/knowledge/ChunkPreviewPanel.vue`（若 KbDocPanel 过大则拆）

- [x] **Step 1: API**

```ts
export type ChunkStrategy = 'markdown' | 'fixed' | 'recursive' | 'semantic' | 'parent_child'

export async function previewChunks(
  tenantId: TenantId, kbId: string, docId: string,
  body: { version?: string; strategy: ChunkStrategy; params: Record<string, number> },
): Promise<ChunkPreviewResponse>

export async function publishDocument(
  tenantId: TenantId, kbId: string, docId: string,
  body: { previewId: string },
): Promise<{ docId: string; docName: string; version: string; chunks: number }>
```

- [x] **Step 2: UI 状态机**

- `strategy` + 动态 params（按策略显示字段，默认值同 spec）
- `previewId` / `previewConfirmed` / `expiresAt`
- 「预览分块」→ 调 API → 展示列表（父子显示 level）
- 「确认此预览」→ `previewConfirmed=true`
- 「发布生效」`:disabled="!previewConfirmed || !previewId"`；点击调 `publishDocument({ previewId })`
- `watch(strategy/params)` → 清空 preview 状态
- semantic：按钮 loading 文案「正在计算语义边界…」
- 已发布：展示 `chunkStrategy`（详情 API 若无该字段则扩展 DocumentDetail）

- [x] **Step 3: `npx vue-tsc -b` in sunshine-ui → Commit** `feat(ui): document chunk strategy preview gate`

---

## Task 14: 去掉前端参数配置 Tab 分段项

**Files:**
- Modify: `sunshine-ui/src/composables/useKbConfigPanel.ts` — 去掉 `'rag-chunk': ['chunk']`
- Modify: `sunshine-ui/src/components/knowledge/kbConfigFieldHelp.ts` — 删除 `rag-chunk*` 文案
- Modify: `sunshine-ui/src/api/ragAdmin/kbConfigTypes.ts` — 去掉 `chunkMaxSize`（若类型仍引用）

- [x] **Step 1: 确认 Config Tab 不再渲染分段分组（schema 已无 rag-chunk 则自然消失；前端清理兜底）**

- [x] **Step 2: vue-tsc → Commit** `refactor(ui): drop rag-chunk from knowledge config panel`

---

## Task 15: bulk 脚本 + Live 验收

**Files:**
- Modify: `scripts/rag_ingest_bulk.py` — `--strategy`（默认 `markdown`）、可选 `--max-size` 等；对每篇：若仍走 `ingest/text`，body 带 `strategy`+`params`；**或** 改为 upload/draft → preview → publish（优先扩展 `ingest/text` 与 Task 9 一致）
- Create: `scripts/verify_chunk_strategies_live.py`
- Modify: `docs/rag/README.md`（命令表加一行）、`docs/rag/backlog.md`（勾选本能力）
- Modify: `CLAUDE.md` 运维脚本表（一行）

- [x] **Step 1: Live 脚本最小用例**

1. 建临时文档 / 用 ingest  
2. 五策略各 preview→publish（或 ingest）  
3. `POST /api/rag/search` 能命中  
4. parent_child：断言返回文本长度接近 parentSize（父上下文）  
5. 无 previewId publish → 期望失败  

- [x] **Step 2: 本地跑**

```bash
python scripts/verify_chunk_strategies_live.py --rag-url http://ecs4c16g:8400
```

Expected: 全 PASS

- [x] **Step 3: Commit** `test(rag): live verify multi strategy chunking`

---

## 自我复盘（对照 spec）

| Spec 条款 | 任务 |
|-----------|------|
| Preview-Token 门禁 | T8–T9 |
| 五策略 | T2–T5 |
| 文档级 params + version 落库 | T1、T7、T9 |
| 删 KB rag-chunk | T12、T14 |
| 必须预览 UI | T13 |
| parent_child 检索回填 | T10–T11 |
| bulk 走门禁 | T9 ingestText、T15 |
| chunk 上限 2000 | T6 |
| 默认 markdown | T9 / T13 / T15 |

无 TBD；类型名全程统一 `ChunkStrategy` / `ChunkParams` / `ChunkDraft` / `previewId`。
