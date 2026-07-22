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
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对话历史 chunk 向量库 — collection {@code sunshine_chat_history}，
 * 与企业 KB、旧 {@code sunshine_memory_mtm} 分离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryMilvusService {

    static final String COLLECTION = "sunshine_chat_history";
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
            log.info("[ChatHistory] Collection '{}' 已存在", COLLECTION);
            return;
        }
        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Sunshine 对话历史 chunk")
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
                        .withName("msg_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_index")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("content")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(8192)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("created_at")
                        .withDataType(DataType.Int64)
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
        log.info("[ChatHistory] Collection '{}' 创建完成", COLLECTION);
    }

    public void deleteByMsgId(String userId, String tenantId, String msgId) {
        String expr = "user_id == \"" + escape(userId)
                + "\" && tenant_id == \"" + escape(tenantId)
                + "\" && msg_id == \"" + escape(msgId) + "\"";
        client.delete(DeleteParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr)
                .build());
    }

    /** Admin：按会话列出已索引 chunk（无向量字段）。 */
    public List<ChatHistoryChunk> listByConv(String userId, String tenantId, String convId, int limit) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(convId)) {
            return List.of();
        }
        String tid = StringUtils.hasText(tenantId) ? tenantId : "default";
        int lim = Math.max(1, Math.min(limit, 500));
        String expr = "user_id == \"" + escape(userId)
                + "\" && tenant_id == \"" + escape(tid)
                + "\" && conv_id == \"" + escape(convId) + "\"";
        R<io.milvus.grpc.QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr)
                .withOutFields(List.of("msg_id", "chunk_index", "content", "created_at", "conv_id"))
                .withLimit((long) lim)
                .build());
        if (response.getData() == null) {
            log.warn("[ChatHistory] listByConv 空: {}", response.getMessage());
            return List.of();
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<?> msgIds = wrapper.getFieldWrapper("msg_id").getFieldData();
        List<?> indexes = wrapper.getFieldWrapper("chunk_index").getFieldData();
        List<?> contents = wrapper.getFieldWrapper("content").getFieldData();
        List<?> createdAts = wrapper.getFieldWrapper("created_at").getFieldData();
        int size = contents != null ? contents.size() : 0;
        List<ChatHistoryChunk> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object content = contents.get(i);
            if (content == null) {
                continue;
            }
            int chunkIndex = indexes != null && i < indexes.size() && indexes.get(i) instanceof Number n
                    ? n.intValue() : i;
            long createdAt = 0L;
            if (createdAts != null && i < createdAts.size() && createdAts.get(i) instanceof Number num) {
                createdAt = num.longValue();
            }
            out.add(new ChatHistoryChunk(
                    convId,
                    fieldAt(msgIds, i),
                    chunkIndex,
                    content.toString(),
                    createdAt));
        }
        out.sort(Comparator
                .comparingLong(ChatHistoryChunk::createdAtMs).reversed()
                .thenComparingInt(ChatHistoryChunk::chunkIndex));
        return List.copyOf(out);
    }

    public void insertChunks(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            long createdAtMs,
            List<ChunkRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int n = rows.size();
        List<String> userIds = new ArrayList<>(n);
        List<String> tenantIds = new ArrayList<>(n);
        List<String> convIds = new ArrayList<>(n);
        List<String> msgIds = new ArrayList<>(n);
        List<Long> indexes = new ArrayList<>(n);
        List<String> contents = new ArrayList<>(n);
        List<Long> times = new ArrayList<>(n);
        List<List<Float>> embeddings = new ArrayList<>(n);
        for (ChunkRow row : rows) {
            userIds.add(userId);
            tenantIds.add(tenantId);
            convIds.add(convId);
            msgIds.add(msgId);
            indexes.add((long) row.chunkIndex());
            contents.add(truncate(row.content(), 8192));
            times.add(createdAtMs);
            embeddings.add(row.embedding());
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("user_id", userIds));
        fields.add(new InsertParam.Field("tenant_id", tenantIds));
        fields.add(new InsertParam.Field("conv_id", convIds));
        fields.add(new InsertParam.Field("msg_id", msgIds));
        fields.add(new InsertParam.Field("chunk_index", indexes));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("created_at", times));
        fields.add(new InsertParam.Field("embedding", embeddings));
        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
    }

    public List<ChatHistoryHit> search(String userId, String tenantId, List<Float> queryVector, int topK) {
        String expr = "user_id == \"" + escape(userId) + "\" && tenant_id == \"" + escape(tenantId) + "\"";
        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withExpr(expr)
                .withOutFields(List.of("conv_id", "msg_id", "content", "created_at"))
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build());

        if (result.getData() == null) {
            log.warn("[ChatHistory] 检索返回空: {}", result.getMessage());
            return List.of();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<?> convIds = wrapper.getFieldData("conv_id", 0);
        List<?> msgIds = wrapper.getFieldData("msg_id", 0);
        List<?> contents = wrapper.getFieldData("content", 0);
        List<?> createdAts = wrapper.getFieldData("created_at", 0);
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

        List<ChatHistoryHit> hits = new ArrayList<>();
        int size = contents != null ? contents.size() : 0;
        for (int i = 0; i < size; i++) {
            Object content = contents.get(i);
            if (content == null) {
                continue;
            }
            String convId = fieldAt(convIds, i);
            String msgId = fieldAt(msgIds, i);
            long createdAt = 0L;
            if (createdAts != null && i < createdAts.size() && createdAts.get(i) instanceof Number num) {
                createdAt = num.longValue();
            }
            float score = idScores != null && i < idScores.size() ? idScores.get(i).getScore() : 0f;
            hits.add(new ChatHistoryHit(convId, msgId, content.toString(), score, createdAt));
        }
        return hits;
    }

    private static String fieldAt(List<?> values, int i) {
        if (values == null || i >= values.size() || values.get(i) == null) {
            return "";
        }
        return values.get(i).toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ChunkRow(int chunkIndex, String content, List<Float> embedding) {
    }

    public record ChatHistoryHit(String convId, String msgId, String content, float score, long createdAtMs) {
    }

    public record ChatHistoryChunk(
            String convId, String msgId, int chunkIndex, String content, long createdAtMs) {
    }
}
