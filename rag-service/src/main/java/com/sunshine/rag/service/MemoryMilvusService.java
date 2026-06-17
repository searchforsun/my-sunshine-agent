package com.sunshine.rag.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
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
 * MTM 情景记忆向量库 — collection {@code sunshine_memory_mtm}，与企业知识库分 collection。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryMilvusService {

    private final MilvusServiceClient client;

    static final String COLLECTION = "sunshine_memory_mtm";
    private static final int DIMENSION = 1024;

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
            log.info("[MTM] Collection '{}' 已存在", COLLECTION);
            return;
        }
        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Sunshine 对话情景记忆")
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("user_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("tenant_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("conv_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("summary")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(8192)
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

        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
        log.info("[MTM] Collection '{}' 创建完成", COLLECTION);
    }

    public void upsert(String userId, String tenantId, String convId, String summary, List<Float> embedding) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("user_id", List.of(userId)));
        fields.add(new InsertParam.Field("tenant_id", List.of(tenantId)));
        fields.add(new InsertParam.Field("conv_id", List.of(convId)));
        fields.add(new InsertParam.Field("summary", List.of(summary)));
        fields.add(new InsertParam.Field("embedding", List.of(embedding)));

        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
    }

    public List<MemoryHit> search(String userId, String tenantId, List<Float> queryVector, int topK) {
        String expr = "user_id == \"" + escape(userId) + "\" && tenant_id == \"" + escape(tenantId) + "\"";

        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withExpr(expr)
                .withOutFields(List.of("conv_id", "summary"))
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build());

        if (result.getData() == null) {
            log.warn("[MTM] 检索返回空: {}", result.getMessage());
            return List.of();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<?> convIds = wrapper.getFieldData("conv_id", 0);
        List<?> summaries = wrapper.getFieldData("summary", 0);
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

        List<MemoryHit> hits = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i++) {
            Object summary = summaries.get(i);
            if (summary == null) {
                continue;
            }
            String convId = convIds != null && i < convIds.size() && convIds.get(i) != null
                    ? convIds.get(i).toString()
                    : "";
            float score = idScores != null && i < idScores.size() ? idScores.get(i).getScore() : 0f;
            hits.add(new MemoryHit(convId, summary.toString(), score));
        }
        return hits;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record MemoryHit(String convId, String summary, float score) {
    }
}
