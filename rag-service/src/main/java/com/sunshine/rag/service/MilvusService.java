package com.sunshine.rag.service;

import com.sunshine.rag.model.ChunkInsertRequest;
import com.sunshine.rag.util.DocumentVersionTime;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Milvus 向量数据库操作服务 — V2 schema（kb_id / doc_id / version / status 等）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient client;

    private static final String COLLECTION = "sunshine_knowledge";
    private static final int DIMENSION = 1024;
    private static final Set<String> V2_FIELDS = Set.of(
            "doc_name", "tenant_id", "kb_id", "doc_id", "version",
            "chunk_index", "status", "source_type");

    @PostConstruct
    public void init() {
        ensureCollection();
    }

    private void ensureCollection() {
        R<Boolean> exists = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        if (Boolean.TRUE.equals(exists.getData())) {
            if (schemaSupportsV2()) {
                loadCollection();
                log.info("[RAG] Collection '{}' 已存在且 V2 schema 匹配", COLLECTION);
                return;
            }
            log.warn("[RAG] Collection '{}' schema 过旧，重建以支持 V2 metadata", COLLECTION);
            dropCollection();
        }
        createCollection();
    }

    private boolean schemaSupportsV2() {
        R<io.milvus.grpc.DescribeCollectionResponse> response = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        if (response.getData() == null) {
            return false;
        }
        DescCollResponseWrapper wrapper = new DescCollResponseWrapper(response.getData());
        Set<String> names = wrapper.getFields().stream()
                .map(f -> f.getName())
                .collect(java.util.stream.Collectors.toSet());
        if (!V2_FIELDS.stream().allMatch(names::contains)) {
            return false;
        }
        return wrapper.getFields().stream()
                .filter(f -> "version".equals(f.getName()))
                .findFirst()
                .map(f -> f.getDataType() == DataType.Int64)
                .orElse(false);
    }

    private void dropCollection() {
        client.releaseCollection(ReleaseCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
        client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
    }

    private void createCollection() {
        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Sunshine AI 知识库 V2")
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build())
                .addFieldType(varchar("doc_name", 512))
                .addFieldType(varchar("tenant_id", 32))
                .addFieldType(varchar("kb_id", 64))
                .addFieldType(varchar("doc_id", 128))
                .addFieldType(int64Field("version"))
                .addFieldType(intField("chunk_index"))
                .addFieldType(varchar("status", 16))
                .addFieldType(varchar("source_type", 32))
                .addFieldType(FieldType.newBuilder()
                        .withName("content")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(DIMENSION)
                        .build())
                .build());
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName("embedding")
                .withIndexType(io.milvus.param.IndexType.IVF_FLAT)
                .withMetricType(MetricType.IP)
                .withExtraParam("{\"nlist\":128}")
                .build());
        loadCollection();
        log.info("[RAG] Collection '{}' V2 创建完成", COLLECTION);
    }

    private static FieldType varchar(String name, int maxLen) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.VarChar)
                .withMaxLength(maxLen)
                .build();
    }

    private static FieldType int64Field(String name) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.Int64)
                .build();
    }

    private static FieldType intField(String name) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.Int32)
                .build();
    }

    private void loadCollection() {
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
    }

    public void insert(ChunkInsertRequest req) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("doc_name", List.of(req.docName())));
        fields.add(new InsertParam.Field("tenant_id", List.of(req.tenantId())));
        fields.add(new InsertParam.Field("kb_id", List.of(req.kbId())));
        fields.add(new InsertParam.Field("doc_id", List.of(req.docId())));
        fields.add(new InsertParam.Field("version", List.of(DocumentVersionTime.toMilvusCode(req.version()))));
        fields.add(new InsertParam.Field("chunk_index", List.of(req.chunkIndex())));
        fields.add(new InsertParam.Field("status", List.of(req.status())));
        fields.add(new InsertParam.Field("source_type", List.of(req.sourceType())));
        fields.add(new InsertParam.Field("content", List.of(req.content())));
        fields.add(new InsertParam.Field("embedding", List.of(req.embedding())));
        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
    }

    public List<SearchHit> search(List<Float> queryVector, int topK, String tenantId) {
        return search(queryVector, topK, tenantId, "default");
    }

    public List<SearchHit> search(List<Float> queryVector, int topK, String tenantId, String kbId) {
        String tid = normalizeId(tenantId, "default");
        String kid = normalizeId(kbId, "default");
        String expr = String.format(
                "tenant_id == \"%s\" && kb_id == \"%s\" && status == \"active\"",
                escape(tid), escape(kid));
        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withExpr(expr)
                .withOutFields(List.of("doc_name", "content", "tenant_id", "kb_id"))
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build());
        if (result.getData() == null) {
            log.warn("[RAG] 检索返回空: {}", result.getMessage());
            return List.of();
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<?> docNames = wrapper.getFieldData("doc_name", 0);
        List<?> contents = wrapper.getFieldData("content", 0);
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
        List<SearchHit> results = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            Object content = contents.get(i);
            if (content == null) {
                continue;
            }
            String docName = docNames != null && i < docNames.size() && docNames.get(i) != null
                    ? docNames.get(i).toString()
                    : "未知文档";
            float score = idScores != null && i < idScores.size() ? idScores.get(i).getScore() : 0f;
            results.add(new SearchHit(docName, content.toString(), score));
        }
        log.info("[RAG] 检索完成: tenant={}, kb={}, topK={}, 返回={}", tid, kid, topK, results.size());
        return results;
    }

    private static String normalizeId(String value, String fallback) {
        return value != null && !value.isBlank() ? value.strip() : fallback;
    }

    private static String escape(String value) {
        return escapeForExpr(value);
    }

    static String escapeForExpr(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 删除指定文档版本的全部 chunk（superseded 或重入库前） */
    public void deleteByDocVersion(String tenantId, String kbId, String docId, String version) {
        String expr = docVersionExpr(tenantId, kbId, docId, version);
        client.delete(DeleteParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr)
                .build());
        log.info("[RAG] 删除 chunk: tenant={}, kb={}, doc={}, v={}", tenantId, kbId, docId, version);
    }

    public List<ChunkPreview> queryChunks(String tenantId, String kbId, String docId, String version) {
        String expr = docVersionExpr(tenantId, kbId, docId, version);
        R<io.milvus.grpc.QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr)
                .withOutFields(List.of("doc_name", "content", "chunk_index"))
                .build());
        if (response.getData() == null) {
            return List.of();
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<?> contents = wrapper.getFieldWrapper("content").getFieldData();
        List<?> indices = wrapper.getFieldWrapper("chunk_index").getFieldData();
        List<?> names = wrapper.getFieldWrapper("doc_name").getFieldData();
        List<ChunkPreview> previews = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i) != null ? contents.get(i).toString() : "";
            int idx = indices.get(i) instanceof Number n ? n.intValue() : i;
            String name = names.get(i) != null ? names.get(i).toString() : docId;
            previews.add(new ChunkPreview(idx, name, content));
        }
        previews.sort(java.util.Comparator.comparingInt(ChunkPreview::chunkIndex));
        return previews;
    }

    private static String docVersionExpr(String tenantId, String kbId, String docId, String version) {
        String tid = normalizeId(tenantId, "default");
        String kid = normalizeId(kbId, "default");
        String did = escape(normalizeId(docId, docId));
        long code = DocumentVersionTime.toMilvusCode(version);
        return String.format(
                "tenant_id == \"%s\" && kb_id == \"%s\" && doc_id == \"%s\" && version == %d",
                escape(tid), escape(kid), did, code);
    }

    /** Admin：drop + recreate collection（清库重建） */
    public void rebuildCollection() {
        log.warn("[RAG] Admin rebuild: 清空 collection {}", COLLECTION);
        R<Boolean> exists = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        if (Boolean.TRUE.equals(exists.getData())) {
            dropCollection();
        }
        createCollection();
    }

    public record SearchHit(String docName, String content, float score) {
    }

    public record ChunkPreview(int chunkIndex, String docName, String content) {
    }
}
