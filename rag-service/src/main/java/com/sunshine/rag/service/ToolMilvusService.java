package com.sunshine.rag.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具语义索引向量库 — collection {@code sunshine_tool_index}。
 * 工具目录（name+description+参数摘要）向量化后按租户检索，供 ReAct 分层注入（Tier 2 Top-K schema）使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolMilvusService {

    static final String COLLECTION = "sunshine_tool_index";
    private static final int DIMENSION = 1024;

    private final MilvusServiceClient client;

    @PostConstruct
    public void init() {
        ensureCollection();
    }

    private void ensureCollection() {
        R<Boolean> exists = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        if (Boolean.TRUE.equals(exists.getData())) {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .build());
            log.info("[ToolIndex] Collection '{}' 已存在", COLLECTION);
            return;
        }
        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Sunshine 工具语义索引（tool RAG，租户隔离）")
                .addFieldType(FieldType.newBuilder()
                        .withName("tool_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(128)
                        .withPrimaryKey(true)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("name")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(256)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("description")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(4096)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("tenant_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
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
        // 租户过滤用标量索引
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName("tenant_id")
                .withIndexType(io.milvus.param.IndexType.INVERTED)
                .build());

        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
        log.info("[ToolIndex] Collection '{}' 创建完成", COLLECTION);
    }

    /** 全量重建租户工具索引：先删后插，幂等（工具目录规模小，不做增量）。 */
    public void replaceAll(String tenantId, List<ToolIndexRow> rows) {
        String tid = normalizeTenant(tenantId);
        client.delete(DeleteParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr("tenant_id == \"" + escape(tid) + "\"")
                .build());
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int n = rows.size();
        List<String> ids = new ArrayList<>(n);
        List<String> names = new ArrayList<>(n);
        List<String> descriptions = new ArrayList<>(n);
        List<String> tenants = new ArrayList<>(n);
        List<List<Float>> embeddings = new ArrayList<>(n);
        for (ToolIndexRow row : rows) {
            ids.add(row.toolId());
            names.add(truncate(row.name(), 256));
            descriptions.add(truncate(row.description(), 4096));
            tenants.add(tid);
            embeddings.add(row.embedding());
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("tool_id", ids));
        fields.add(new InsertParam.Field("name", names));
        fields.add(new InsertParam.Field("description", descriptions));
        fields.add(new InsertParam.Field("tenant_id", tenants));
        fields.add(new InsertParam.Field("embedding", embeddings));
        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
        flushAfterWrite();
        log.info("[ToolIndex] 索引重建完成 tenant={} tools={}", tid, rows.size());
    }

    /** BOUNDED 一致性下刚写入的数据不可立即可见；flush 强制落盘，保证 sync 返回后检索即命中。 */
    private void flushAfterWrite() {
        try {
            client.flush(FlushParam.newBuilder()
                    .withCollectionNames(List.of(COLLECTION))
                    .withSyncFlush(true)
                    .withSyncFlushWaitingTimeout(30L)
                    .build());
        } catch (Exception e) {
            log.warn("[ToolIndex] flush 失败（检索可能延迟可见）: {}", e.getMessage());
        }
    }

    public List<ToolIndexHit> search(String tenantId, List<Float> queryVector, int topK) {
        String tid = normalizeTenant(tenantId);
        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withExpr("tenant_id == \"" + escape(tid) + "\"")
                .withOutFields(List.of("tool_id"))
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build());

        if (result.getData() == null) {
            log.warn("[ToolIndex] 检索返回空: {}", result.getMessage());
            return List.of();
        }
        SearchResultData results = result.getData().getResults();
        if (results == null || results.getFieldsDataCount() == 0 || results.getScoresCount() == 0) {
            log.debug("[ToolIndex] 检索无命中 tenant={}", tid);
            return List.of();
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(results);
        List<?> toolIds = wrapper.getFieldData("tool_id", 0);
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
        List<ToolIndexHit> hits = new ArrayList<>();
        int size = toolIds != null ? toolIds.size() : 0;
        for (int i = 0; i < size; i++) {
            Object toolId = toolIds.get(i);
            if (toolId == null) {
                continue;
            }
            float score = idScores != null && i < idScores.size() ? idScores.get(i).getScore() : 0f;
            hits.add(new ToolIndexHit(toolId.toString(), score));
        }
        return hits;
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record ToolIndexRow(String toolId, String name, String description, List<Float> embedding) {
    }

    public record ToolIndexHit(String toolId, float score) {
    }
}
