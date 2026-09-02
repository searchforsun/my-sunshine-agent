# RAG 入库与检索管线优化实施计划（P1/P2/P3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RAG 管线补齐语义元数据、Excel 入库、token 门禁、批量 embedding、PDF 降噪与 embedding 维度守卫（不兼容、重建式上线）。

**Architecture:** 全部改动落在 rag-service（入库/检索）、sunshine-ui `/knowledge`、`scripts/rag_*.py`、`docker/mysql/init/14-sunshine-rag-service.sql`、`docs/nacos/sunshine-rag.yaml`；不新增微服务。Milvus/ES 均走「加字段即重建」路线，无兼容层。

**Tech Stack:** Spring Boot 3.2 / WebFlux / Milvus SDK / Elasticsearch REST / Apache POI / JUnit5 + Mockito / Vue3 + Naive UI / Python3 运维脚本。

**Spec:** [`docs/superpowers/specs/2026-07-27-rag-pipeline-optimization-design.md`](../specs/2026-07-27-rag-pipeline-optimization-design.md)

## Global Constraints

- 禁止 Flyway；SQL 只改 `docker/mysql/init/14-sunshine-rag-service.sql`
- 提示词禁止硬编码进业务代码外的位置；打标 prompt 内聚于 `MetadataExtractionPrompt`（rag-service 自包含，不经 prompt-manager，与 eval suggest 同款）
- `ChatCompletionResponse` 用 `@Builder` 必须加 `@NoArgsConstructor` + `@AllArgsConstructor`
- 不引入「正则去标点」类破坏性清洗；`RepeatedLineCleaner` 宁留勿删
- metadata 检索开关 `search.metadataFilterEnabled` 默认 `false`；开启必须过 corpus-50 门禁
- 代码加适量中文注释；禁止业务代码多余空行
- 改 `docs/nacos/*.yaml` 后必须 `python scripts/sync_nacos.py` 并重启 rag-service
- 每个 Task 结束：`cd rag-service && mvn -q compile`（或全量 `mvn -q -pl rag-service -am compile`）通过 + 相关单测通过

---

## Task 1: TokenEstimator + preview token 透出 + 8192 硬门禁

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/chunker/TokenEstimator.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/exception/RagErrorCode.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkerRegistry.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/dto/ChunkPreviewChunkItem.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkPreviewService.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/chunker/TokenEstimatorTest.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/chunker/ChunkerRegistryTest.java`

**Interfaces:**
- Produces:
  - `TokenEstimator.estimate(String text) → int`（静态，纯函数）
  - `RagErrorCode.CHUNK_TOKEN_LIMIT_EXCEEDED(400, "rag_chunk_token_limit_exceeded", "单块超过 token 上限 8192，请调小 maxSize 或拆分文档")`
  - `ChunkPreviewChunkItem` 增加 `int tokenCount()`
  - `ChunkerRegistry.chunk(...)` 在 reindex 前对每块 `TokenEstimator.estimate(text) > 8192` 抛上述错误码

- [ ] **Step 1: 写失败单测 TokenEstimatorTest**

```java
package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {
    @Test
    void pureChinese() {
        // 100 个 CJK 字 ≈ 60 token
        String text = "中".repeat(100);
        assertEquals(60, TokenEstimator.estimate(text));
    }
    @Test
    void pureAscii() {
        // 100 个 ASCII ≈ 25 token
        String text = "a".repeat(100);
        assertEquals(25, TokenEstimator.estimate(text));
    }
    @Test
    void mixed() {
        String text = "中".repeat(50) + "a".repeat(40); // 30 + 10
        assertEquals(40, TokenEstimator.estimate(text));
    }
    @Test
    void emptyAndNull() {
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate(null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败** `mvn -q -pl rag-service test -Dtest=TokenEstimatorTest`（类不存在编译失败）

- [ ] **Step 3: 实现 TokenEstimator**

```java
package com.sunshine.rag.chunker;

/** 中英文混合 token 估算（text-embedding-v4 / deepseek 经验值，仅用于门禁与展示） */
public final class TokenEstimator {
    private static final double CJK_PER_CHAR = 0.6;
    private static final double ASCII_PER_CHAR = 0.25;
    private static final double OTHER_PER_CHAR = 0.5;

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0, ascii = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                    || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF)) {
                cjk++;
            } else if (c < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk * CJK_PER_CHAR + ascii * ASCII_PER_CHAR + other * OTHER_PER_CHAR);
    }
}
```

- [ ] **Step 4: 跑 TokenEstimatorTest 通过**

- [ ] **Step 5: ChunkerRegistry 加门禁 + RagErrorCode 新增错误码**

`RagErrorCode` 在 `CHUNK_LIMIT_EXCEEDED` 后追加：
```java
CHUNK_TOKEN_LIMIT_EXCEEDED(400, "rag_chunk_token_limit_exceeded", "单块超过 token 上限 8192，请调小 maxSize 或拆分文档"),
```

`ChunkerRegistry` 增加常量与校验：
```java
static final int CHUNK_TOKEN_HARD_LIMIT = 8192;
// chunk(...) 内 reindex 之前：
for (ChunkDraft draft : drafts) {
    if (TokenEstimator.estimate(draft.text()) > CHUNK_TOKEN_HARD_LIMIT) {
        throw new BizException(RagErrorCode.CHUNK_TOKEN_LIMIT_EXCEEDED);
    }
}
```

- [ ] **Step 6: ChunkerRegistryTest 增加 token 超限用例**（构造一块 >8192 token 的文本走 fixed 策略断言抛错）；跑 `ChunkerRegistryTest` 通过

- [ ] **Step 7: preview 透出 tokenCount**

`ChunkPreviewChunkItem` 增加 `int tokenCount`；`ChunkPreviewService` 构建 item 时填 `TokenEstimator.estimate(text)`。同步改 `ChunkPreviewServiceTest` 断言。编译 + 跑 `ChunkPreviewServiceTest` 通过。

- [ ] **Step 8: 编译 + 提交**

```bash
mvn -q -pl rag-service -am compile
git add rag-service/src/main/java/com/sunshine/rag/chunker/TokenEstimator.java rag-service/src/main/java/com/sunshine/rag/exception/RagErrorCode.java rag-service/src/main/java/com/sunshine/rag/chunker/ChunkerRegistry.java rag-service/src/main/java/com/sunshine/rag/admin/catalog/dto/ChunkPreviewChunkItem.java rag-service/src/main/java/com/sunshine/rag/chunker/ChunkPreviewService.java rag-service/src/test/java/com/sunshine/rag/chunker
git commit -m "feat(rag): token 估算与 8192 单块硬门禁，preview 透出 tokenCount"
```

---

## Task 2: EmbeddingService.embedBatch + SemanticChunker/Indexer 批量化

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/EmbeddingService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/SemanticChunker.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentChunkIndexer.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/service/EmbeddingServiceBatchTest.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/chunker/SemanticChunkerTest.java`

**Interfaces:**
- Consumes: 现有 `EmbeddingService.embed(String)`
- Produces: `EmbeddingService.embedBatch(List<String> texts) → Mono<List<List<Float>>>`（保序；单请求 ≤10 条，批间 concurrency=4）

- [ ] **Step 1: 写失败单测 EmbeddingServiceBatchTest**（Mock WebClient 或抽出请求执行函数；至少覆盖：>10 条自动分片、顺序保持、空列表返回空）

- [ ] **Step 2: 实现 embedBatch**

```java
public Mono<List<List<Float>>> embedBatch(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
        return Mono.just(List.of());
    }
    List<List<String>> shards = new ArrayList<>();
    for (int i = 0; i < texts.size(); i += 10) {
        shards.add(texts.subList(i, Math.min(i + 10, texts.size())));
    }
    return Flux.fromIterable(shards)
            .flatMapSequential(this::embedShard, 4)
            .collectList()
            .map(batches -> batches.stream().flatMap(List::stream).toList());
}

private Mono<List<List<Float>>> embedShard(List<String> shard) {
    Map<String, Object> body = Map.of(
            "model", model,
            "input", Map.of("texts", shard),
            "parameters", Map.of("text_type", "document"));
    return client().post().bodyValue(body).retrieve().bodyToMono(Map.class)
            .map(this::parseEmbeddings)
            .doOnError(e -> log.error("[RAG] Embedding 批量调用失败", e));
}
```
（`parseEmbeddings` 抽出原 `embed` 中的 response 解析为私有方法，`embed` 复用。）

- [ ] **Step 3: SemanticChunker 改用批量**

`chunk(...)` 中将逐句 for 循环替换为：
```java
List<List<Float>> embeddings = embeddingService.embedBatch(sentences).block();
if (embeddings == null || embeddings.size() != sentences.size()) {
    throw new IllegalStateException("embedBatch 返回数量与句子数不一致");
}
```
同步更新 `SemanticChunkerTest`：mock `embedBatch` 返回与句子等长向量，断言切分结果与原逐句逻辑一致。

- [ ] **Step 4: DocumentChunkIndexer.embedAndIndexDrafts 批量化**

改为先一次性批量 embed 再循环 insert（保持 boundedElastic）：
```java
List<String> texts = drafts.stream().map(ChunkDraft::text).toList();
return embeddingService.embedBatch(texts)
        .publishOn(Schedulers.boundedElastic())
        .doOnNext(vectors -> {
            for (int i = 0; i < drafts.size(); i++) {
                ChunkDraft draft = drafts.get(i);
                List<Float> vector = vectors.get(i);
                String chunkId = ChunkIds.chunkId(docId, version, draft.index());
                String chunkLevel = resolveChunkLevel(strategy, draft.meta());
                String parentChunkId = parentChunkIdFromMeta(docId, version, draft.meta());
                ChunkInsertRequest req = new ChunkInsertRequest(
                        docName, draft.text(), vector, tenantId, kbId, docId,
                        version, draft.index(), "active", strategyWire,
                        strategyWire, chunkLevel, parentChunkId);
                milvusService.insert(req);
                elasticsearchIndexService.indexChunk(
                        chunkId, docName, draft.text(), draft.index(), tenantId,
                        kbId, docId, version, "active", strategyWire,
                        strategyWire, chunkLevel, parentChunkId);
            }
        })
        .then();
```

- [ ] **Step 5: 编译 + 相关单测通过 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest='SemanticChunkerTest,EmbeddingServiceBatchTest,DocumentChunkIndexerTest'
git add rag-service/src/main/java/com/sunshine/rag/service/EmbeddingService.java rag-service/src/main/java/com/sunshine/rag/chunker/SemanticChunker.java rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentChunkIndexer.java rag-service/src/test/java/com/sunshine/rag/service/EmbeddingServiceBatchTest.java rag-service/src/test/java/com/sunshine/rag/chunker/SemanticChunkerTest.java
git commit -m "feat(rag): embedding 批量化，semantic 分块与入库索引提速"
```

---

## Task 3: RepeatedLineCleaner（PDF 页眉页脚/水印剥离）

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/RepeatedLineCleaner.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/PdfDocumentParser.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/admin/catalog/parser/RepeatedLineCleanerTest.java`

**Interfaces:**
- Produces: `RepeatedLineCleaner.clean(String markdown) → String`（静态，纯函数）
- `PdfDocumentParser.parseBytes` 两条路径（文本层 / OCR）返回前调用 `RepeatedLineCleaner.clean(...)`

- [ ] **Step 1: 写失败单测**（覆盖：≥3 页页眉剥离、页码行 `第3页`/`- 3 -`、`3/12` 剥离、表格 `|` 行不删、`#` 标题不删、单页文档保守）

- [ ] **Step 2: 实现**（规则见 spec §4.6：候选行长度 4–60、页数≥3 且频次>40%、页码模式单行即删、保守排除 `|`/`#` 开头行；虚拟页按 \f 或 50 行切）

- [ ] **Step 3: 接入 PdfDocumentParser**

```java
// 文本层分支
return RepeatedLineCleaner.clean(textLayer.get());
// OCR 分支
return RepeatedLineCleaner.clean(dashScopeOcrService.ocrPdf(bytes, progress));
```

- [ ] **Step 4: 单测通过 + 编译 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest=RepeatedLineCleanerTest
git add rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/RepeatedLineCleaner.java rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/PdfDocumentParser.java rag-service/src/test/java/com/sunshine/rag/admin/catalog/parser/RepeatedLineCleanerTest.java
git commit -m "feat(rag): PDF 页眉页脚/水印重复行清洗"
```

---

## Task 4: Milvus 维度守卫 + metadata 标量字段（重建式）

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/EmbeddingService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`
- Test: `rag-service/src/test/java/com/sunshine/rag/service/MilvusServiceSchemaTest.java`

**Interfaces:**
- Produces:
  - `EmbeddingService.getDimension() → int`（`@Value("${embedding.dimension:1024}")`）
  - Milvus collection 新增字段：`keywords Array<VarChar(64)>`、`topics Array<VarChar(64)>`、`years Array<Int16>`、`doc_nos Array<VarChar(128)>`、`section_path VarChar(512)`
  - `V2_FIELDS` 扩充；`ensureCollection()` 在 schema 不匹配时重建；**维度不匹配抛 IllegalStateException**

- [ ] **Step 1: Nacos 显式声明维度**

`sunshine-rag.yaml` `embedding:` 下加：
```yaml
  dimension: 1024   # 换模型必须同步修改并 rag_reset.py 重建
```

- [ ] **Step 2: EmbeddingService 注入 dimension + getter**

- [ ] **Step 3: MilvusService 构造器注入 dimension（移除 1024 硬编码）+ createCollection 加 5 字段 + V2_FIELDS 扩充 + ensureCollection 维度校验 fail-fast**

```java
private final int dimension; // 构造器注入
// ensureCollection 内 schemaSupportsV2 之后追加：
int existingDim = wrapper.getFields().stream()
        .filter(f -> "embedding".equals(f.getName())).findFirst()
        .map(f -> (int) f.getDimension()).orElse(-1);
if (existingDim != dimension) {
    throw new IllegalStateException(
        "Milvus embedding 维度 " + existingDim + " 与配置 embedding.dimension=" + dimension + " 不一致，请修正配置后 rag_reset.py 重建");
}
```

- [ ] **Step 4: 单测（mock MilvusServiceClient describeCollection 返回维度不一致断言抛错；字段缺失断言触发重建）+ 编译 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest=MilvusServiceSchemaTest
git add rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java rag-service/src/main/java/com/sunshine/rag/service/EmbeddingService.java docs/nacos/sunshine-rag.yaml rag-service/src/test/java/com/sunshine/rag/service/MilvusServiceSchemaTest.java
git commit -m "feat(rag): embedding 维度配置化 + Milvus metadata 标量字段与启动守卫"
```

---

## Task 5: DocumentMetadata 模型 + 规则/LLM enricher + SQL

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/metadata/DocumentMetadata.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/metadata/MetadataExtractionPrompt.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/metadata/DocumentMetadataEnricher.java`
- Modify: `docker/mysql/init/14-sunshine-rag-service.sql`
- Modify: `rag-service/src/main/java/com/sunshine/rag/entity/DocumentVersionEntity.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/admin/catalog/metadata/DocumentMetadataEnricherTest.java`

**Interfaces:**
- Produces:
  - `record DocumentMetadata(List<String> keywords, List<String> topics, List<Integer> years, List<String> docNos)`（不可空，空集合兜底）+ `toJson()/fromJson(String)`
  - `DocumentMetadataEnricher.extract(String fullText) → DocumentMetadata`（规则必跑；LLM 失败降级空集合）
  - `DocumentVersionEntity.docMetadataJson` 字段

- [ ] **Step 1: SQL ALTER**

`14-sunshine-rag-service.sql` 追加：
```sql
ALTER TABLE document_version
    ADD COLUMN doc_metadata_json JSON NULL AFTER chunk_params_json;
```

- [ ] **Step 2: 写失败单测**（年份/文号正则、LLM 返回非法 JSON 降级、空正文返回空集合、toJson/fromJson 往返）

- [ ] **Step 3: 实现 DocumentMetadata + MetadataExtractionPrompt + DocumentMetadataEnricher**

```java
public final class MetadataExtractionPrompt {
    public static final String SYSTEM = """
        你是企业知识库文档标注专家。阅读文档开头，输出纯 JSON（不要 markdown 代码块）：
        {"keywords":["…"],"topics":["…"]}
        keywords：3~8 个核心关键词（名词/术语，≤10 字）；topics：1~4 个所属主题/业务域。
        只输出 JSON。""";
    private MetadataExtractionPrompt() {}
}
```

```java
@Component
@RequiredArgsConstructor
public class DocumentMetadataEnricher {
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}\\s*年?");
    private static final Pattern DOC_NO = Pattern.compile("[一-龥]{1,6}(?:发|办|规|函)?〔\\d{4}〕\\d+号");
    private static final int LLM_INPUT_CHARS = 4000;

    private final LlmGatewayClient llmGatewayClient;
    private final RagLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public DocumentMetadata extract(String fullText) {
        List<Integer> years = ...; // 正则去重，≤8
        List<String> docNos = ...; // 正则去重，≤8
        List<String> keywords = List.of();
        List<String> topics = List.of();
        if (fullText != null && !fullText.isBlank()) {
            String head = fullText.substring(0, Math.min(LLM_INPUT_CHARS, fullText.length()));
            try {
                String raw = llmGatewayClient.complete(llmProperties.getDefaultModel(), MetadataExtractionPrompt.SYSTEM, head);
                // 解析 JSON；异常/非法 → 保持空集合 + warn
            } catch (Exception e) {
                log.warn("[RAG] 元数据 LLM 打标失败，降级为空: {}", e.getMessage());
            }
        }
        return new DocumentMetadata(keywords, topics, years, docNos);
    }
}
```

- [ ] **Step 4: DocumentVersionEntity 增加字段**

```java
@Column(name = "doc_metadata_json", columnDefinition = "JSON")
private String docMetadataJson;
```

- [ ] **Step 5: 单测 + 编译 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest=DocumentMetadataEnricherTest
git add rag-service/src/main/java/com/sunshine/rag/admin/catalog/metadata docker/mysql/init/14-sunshine-rag-service.sql rag-service/src/main/java/com/sunshine/rag/entity/DocumentVersionEntity.java rag-service/src/test/java/com/sunshine/rag/admin/catalog/metadata
git commit -m "feat(rag): 文档级语义元数据 enricher（规则+LLM）与 doc_metadata_json"
```

---

## Task 6: section_path 注入 ChunkDraft + preview/publish/ingestText 串联 metadata

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkDraft.java`（如需便捷 meta 方法）
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/MarkdownChunker.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/parser/MarkdownParser.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/ParentChildChunker.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkPreviewService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/chunker/ChunkPreviewRecord.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentCatalogService.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/chunker/MarkdownChunkerTest.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/chunker/ChunkPreviewServiceTest.java`

**Interfaces:**
- Consumes: `DocumentMetadataEnricher.extract`、`TokenEstimator`
- Produces:
  - markdown 策略每块 `meta.sectionPath`（标题栈路径，`/` 连接）；parent_child parent 写自身、child 继承 parent；其余策略空串
  - `ChunkPreviewRecord` 增加 `DocumentMetadata metadata()`
  - `ChunkPreviewService.createPreview(..., String content, ..., DocumentMetadata metadata)`（或新增重载，避免破坏现有签名则加参数）
  - `DocumentCatalogService.publishVersion`/`ingestText`：preview 前先 `metadataEnricher.extract(content)`；publish 后写 `document_version.doc_metadata_json`

- [ ] **Step 1: MarkdownParser 标题栈暴露**（在 parse 过程中为每个 chunk 记录所属标题路径；调整返回结构或提供 `parseWithSections(String,int) → List<SectionChunk(text, sectionPath)>`；`MarkdownChunker` 转为写 `meta.sectionPath`）

- [ ] **Step 2: ParentChildChunker 让 child 继承 parent 的 sectionPath**（splitParents 后先对各 parent 文本跑标题栈提取首行标题作为路径，child meta 增加 `sectionPath`）

- [ ] **Step 3: 单测更新**（markdown 多级标题路径、parent_child 继承、非 markdown 策略 meta 无 sectionPath）

- [ ] **Step 4: ChunkPreviewRecord/ChunkPreviewService 携带 metadata**

- [ ] **Step 5: DocumentCatalogService 串联**

`chunkPreview(...)` 与 `publishVersion(...)` 与 `ingestText(...)` 三处：内容确定后先 `DocumentMetadata metadata = metadataEnricher.extract(content)`；传入 preview；`activatePublishedVersion`/`ingestText` 落库 `versionEntity.setDocMetadataJson(metadata.toJson())`。

- [ ] **Step 6: 编译 + 相关单测 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest='MarkdownChunkerTest,ParentChildChunkerTest,ChunkPreviewServiceTest,DocumentCatalogServiceTest'
git add rag-service/src/main/java/com/sunshine/rag/chunker rag-service/src/main/java/com/sunshine/rag/parser rag-service/src/main/java/com/sunshine/rag/admin/catalog rag-service/src/test/java/com/sunshine/rag/chunker
git commit -m "feat(rag): section_path 注入与 preview/publish 元数据串联"
```

---

## Task 7: Milvus/ES 双写 metadata + 透出

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/model/ChunkInsertRequest.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/ElasticsearchIndexService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentChunkIndexer.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/model/RetrievalCandidate.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/Bm25SearchService.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/service/ElasticsearchIndexServiceTest.java`（或新增）
- Test: `rag-service/src/test/java/com/sunshine/rag/admin/catalog/DocumentChunkIndexerTest.java`

**Interfaces:**
- Consumes: `DocumentMetadata`、`ChunkDraft.meta.sectionPath`
- Produces:
  - `ChunkInsertRequest` 增加 `keywords/topics/years/docNos/sectionPath`
  - `MilvusService.insert` 写 5 新字段；`search` `withOutFields` 增加 `keywords/section_path`；`SearchHit` 增加 `keywords`
  - `ElasticsearchIndexService.indexChunk` 增加同名参数；`ensureIndex` mapping 增加 `keywords/topics/doc_nos(text+keyword)`、`years(integer)`
  - `RetrievalCandidate` 增加 `List<String> keywords` + `withKeywords`；BM25 `parseHits` 解析 `_source.keywords`

- [ ] **Step 1: 写失败单测**（indexChunk 新字段进 body、ChunkInsertRequest 新字段构造、BM25 parseHits 解析 keywords）

- [ ] **Step 2: 逐文件实现**（ChunkInsertRequest 增加 compact 构造重载避免破坏旧签名；DocumentChunkIndexer 在 publish/ingest 路径把 preview 的 metadata 展平进每个 chunk）

- [ ] **Step 3: ES ensureIndex mapping 追加**

```java
propertiesMap.put("keywords", Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword"))));
propertiesMap.put("topics", Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword"))));
propertiesMap.put("doc_nos", Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword"))));
propertiesMap.put("years", Map.of("type", "integer"));
```

- [ ] **Step 4: 编译 + 单测 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest='DocumentChunkIndexerTest,ElasticsearchIndexServiceTest'
git add rag-service/src/main/java/com/sunshine/rag/model rag-service/src/main/java/com/sunshine/rag/service rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentChunkIndexer.java rag-service/src/test/java/com/sunshine/rag
git commit -m "feat(rag): Milvus/ES 双写语义元数据并透出 keywords"
```

---

## Task 8: 检索侧 metadata 应用（默认关闭）

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/config/EffectiveRagConfig.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/config/RagConfigSchemaService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/Bm25SearchService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/RetrievalService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`（eval suggest 允许路径）
- Test: `rag-service/src/test/java/com/sunshine/rag/service/RetrievalServiceTest.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/service/Bm25SearchServiceTest.java`

**Interfaces:**
- Consumes: `RetrievalCandidate.keywords`、`EffectiveRagConfig`
- Produces:
  - `EffectiveRagConfig` 增加 `metadataFilterEnabled(boolean)`、`metadataKeywordBoost(float)`、`metadataMinKeywordHit(int)` + merge 逻辑
  - schema 新增 3 个 search 字段（默认 false / 2.0 / 2）
  - `Bm25SearchService.buildSearchBody(..., float keywordBoost)`：开关开时 fields=`[content^2, doc_name, keywords^{boost}]`，关时保持 `[content^2, doc_name]`
  - `RetrievalService.metadataFilter(candidates, query, config)`：开关关→原样；开→仅过滤 keywords 非空且命中数 < minKeywordHit 的候选（大小写不敏感子串匹配）；空 keywords 候选保留

- [ ] **Step 1: 写失败单测 RetrievalServiceTest**（开关关不过滤 / 开+空 keywords 保留 / 开+命中不足丢弃 / 开+命中足够保留）

- [ ] **Step 2: 写失败单测 Bm25SearchServiceTest**（开=含 keywords^2 字段，关=退化为两字段）

- [ ] **Step 3: 实现**（RetrievalService 在 `applyMinScore` 之前插入 `metadataFilter`；注意锚点门禁在其之前保持不动）

- [ ] **Step 4: Nacos eval suggest 输出约束追加允许路径** `search.metadataFilterEnabled`

- [ ] **Step 5: 编译 + 单测 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest='RetrievalServiceTest,Bm25SearchServiceTest'
git add rag-service/src/main/java/com/sunshine/rag/admin/config rag-service/src/main/java/com/sunshine/rag/service docs/nacos/sunshine-rag.yaml rag-service/src/test/java/com/sunshine/rag/service
git commit -m "feat(rag): 检索元数据过滤与 BM25 关键词加权（默认关闭）"
```

---

## Task 9: XlsxDocumentParser + sourceType 全链路

**Files:**
- Create: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/XlsxDocumentParser.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentSourceType.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/DocumentFileParser.java`
- Test: `rag-service/src/test/java/com/sunshine/rag/admin/catalog/parser/XlsxDocumentParserTest.java`

**Interfaces:**
- Produces:
  - `DocumentSourceType.XLSX("xlsx", "请上传 Excel（.xlsx）文件，系统将解析为 Markdown 表格。")`（inlineEditable=false，accept `.xlsx`）
  - `XlsxDocumentParser.parse(byte[], ParseProgressListener) → String`（`## sheetName` + Markdown 表格，复用 `DocxDocumentParser.tableToMarkdown` 转义规则）
  - `DocumentFileParser.parse/parseBytes` 支持 XLSX；`isAsyncSourceType` 含 XLSX

- [ ] **Step 1: 写失败单测**（多 sheet、合并单元格取左上值、公式取缓存值、空表抛 INGEST_PARSE_FAILED、`|`/换行转义）

- [ ] **Step 2: 实现 XlsxDocumentParser**（`WorkbookFactory.create`；行级遍历；进度按 sheet 回调）

- [ ] **Step 3: DocumentSourceType + DocumentFileParser 接入**

- [ ] **Step 4: 编译 + 单测 + 提交**

```bash
mvn -q -pl rag-service -am compile && mvn -q -pl rag-service test -Dtest=XlsxDocumentParserTest
git add rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/XlsxDocumentParser.java rag-service/src/main/java/com/sunshine/rag/admin/catalog/DocumentSourceType.java rag-service/src/main/java/com/sunshine/rag/admin/catalog/parser/DocumentFileParser.java rag-service/src/test/java/com/sunshine/rag/admin/catalog/parser/XlsxDocumentParserTest.java
git commit -m "feat(rag): xlsx 解析为 Markdown 表格入库"
```

---

## Task 10: 前端 xlsx + tokenCount/元数据展示

**Files:**
- Modify: `sunshine-ui/src/utils/docSourceTypes.ts`
- Modify: `sunshine-ui/src/components/knowledge/KbDocPanel.vue`
- Modify: `sunshine-ui/src/api/ragAdmin/kbDocuments.ts`
- Test: 前端单测（如已有对应 spec）或手动联调记录

**Interfaces:**
- Consumes: 后端 preview 响应 `tokenCount`、文档详情 `docMetadata`
- Produces: `DocSourceType` 增加 `'xlsx'`；preview 面板每块显示 `chars / tokens`；详情抽屉展示关键词/主题/文号

- [ ] **Step 1: docSourceTypes.ts 增加 xlsx option**（label `Excel`，accept `.xlsx`，inlineEditable=false，markdownPreview=true）

- [ ] **Step 2: kbDocuments.ts 类型补充 `tokenCount` / `docMetadata`**

- [ ] **Step 3: KbDocPanel preview 列表每块展示 tokenCount；详情区展示 docMetadata**

- [ ] **Step 4: 前端构建通过 + 提交**

```bash
cd sunshine-ui && npm run build
git add sunshine-ui/src/utils/docSourceTypes.ts sunshine-ui/src/components/knowledge/KbDocPanel.vue sunshine-ui/src/api/ragAdmin/kbDocuments.ts
git commit -m "feat(ui): knowledge 支持 xlsx 与 chunk token/元数据展示"
```

---

## Task 11: 运维脚本与 Live 验收

**Files:**
- Modify: `scripts/rag_ingest_bulk.py`
- Modify: `scripts/verify_chunk_strategies_live.py`
- Create: `scripts/verify_rag_metadata_live.py`

**Interfaces:**
- Consumes: 后端全部能力
- Produces:
  - `rag_ingest_bulk.py --source-type xlsx` 批量入库
  - `verify_chunk_strategies_live.py` 断言 preview 含 `tokenCount`、xlsx 端到端
  - `verify_rag_metadata_live.py`：上传含关键词文档 → 断言 Milvus/ES chunk 携带 keywords/section_path → 开 `metadataFilterEnabled` 后离题 query 空召回提升

- [ ] **Step 1: rag_ingest_bulk.py 支持 xlsx**

- [ ] **Step 2: verify_chunk_strategies_live.py 扩展断言**

- [ ] **Step 3: 新增 verify_rag_metadata_live.py**

- [ ] **Step 4: 提交**

```bash
git add scripts/rag_ingest_bulk.py scripts/verify_chunk_strategies_live.py scripts/verify_rag_metadata_live.py
git commit -m "feat(scripts): xlsx 批量入库与 RAG metadata live 验收"
```

---

## Task 12: 上线重建 + corpus-50 回归

**Files:**
- 运维动作（无代码提交，记录到 `docs/rag/README.md` 或实施记录）

- [ ] **Step 1: 执行 SQL ALTER**
- [ ] **Step 2: `python scripts/sync_nacos.py` → 重启 rag-service**（触发 Milvus 重建 + ES ensureIndex 新 mapping）
- [ ] **Step 3: 删除 ES 旧索引**（`curl -XDELETE http://ecs4c16g:9200/sunshine_rag_chunks`）→ 重启 rag-service 让其重建
- [ ] **Step 4: `python scripts/rag_wipe_and_ingest.py` 全量重灌**
- [ ] **Step 5: `python scripts/rag_eval.py` 基线回归**（metadataFilterEnabled=false 下门禁必须通过）
- [ ] **Step 6: `python scripts/verify_rag_metadata_live.py` Live 验收**
- [ ] **Step 7: 如需开启 metadataFilterEnabled → 配置打开后复跑 `rag_eval.py` 对比报告**

---

## Self-Review 结论

- **Spec 覆盖**：P1 元数据（T5/T6/T7/T8）、P1 Excel（T9/T10/T11）、P2 token（T1）、P2 批量 embed（T2）、P3 清洗（T3）、P3 维度守卫（T4）、重建上线（T12）、验收（T11/T12）——全覆盖。
- **Placeholder 扫描**：无 TBD/TODO；每个 Task 给出具体类、字段、代码骨架与命令。
- **类型一致性**：`DocumentMetadata`/`TokenEstimator.estimate`/`embedBatch`/`metadataFilterEnabled` 等命名跨 Task 一致；`RetrievalCandidate.keywords` 在 T7 定义、T8 消费。

**执行方式（请选）**：
1. **Subagent-Driven（推荐）**——每个 Task 派一个全新子代理执行，我在任务间做两阶段评审
2. **Inline Execution**——本会话内按 executing-plans 批量执行，分批检查点

回复选择哪种，或直接说「开始」我将按推荐方式执行。
