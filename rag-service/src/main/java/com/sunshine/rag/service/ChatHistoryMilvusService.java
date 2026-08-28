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
 * 与企业 KB 分离（独立 collection）。
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
                .withDescription("Sunshine 对话历史 chunk（scene=chat|task 隔离；layer=body|semantic|process）")
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
                        .withName("scene")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(16)
                        .withDefaultValue("chat")
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("layer")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(16)
                        .withDefaultValue("body")
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("status")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(16)
                        .withDefaultValue("active")
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
        // scene/layer 过滤用标量索引（search expr 命中过滤字段）
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName("scene")
                .withIndexType(io.milvus.param.IndexType.INVERTED)
                .build());
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName("layer")
                .withIndexType(io.milvus.param.IndexType.INVERTED)
                .build());
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName("status")
                .withIndexType(io.milvus.param.IndexType.INVERTED)
                .build());

        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
        log.info("[ChatHistory] Collection '{}' 创建完成（scene+layer+status）", COLLECTION);
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

    /** Admin：按会话列出已索引 chunk（无向量字段）。v28 透出 layer，供展示链路按层过滤（对话面板仅 semantic 摘要）。 */
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
                .withOutFields(List.of("msg_id", "chunk_index", "content", "created_at", "conv_id", "layer"))
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
        List<?> layers = wrapper.getFieldWrapper("layer").getFieldData();
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
                    createdAt,
                    fieldAt(layers, i)));
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
        insertChunks(userId, tenantId, convId, msgId, createdAtMs, rows, "chat", "body");
    }

    /** scene=chat|task；layer=body|semantic|process；status 默认 active（冲突打标后为 conflict）。 */
    public void insertChunks(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            long createdAtMs,
            List<ChunkRow> rows,
            String scene,
            String layer) {
        insertChunks(userId, tenantId, convId, msgId, createdAtMs, rows, scene, layer, "active");
    }

    public void insertChunks(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            long createdAtMs,
            List<ChunkRow> rows,
            String scene,
            String layer,
            String status) {
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
        List<String> scenes = new ArrayList<>(n);
        List<String> layers = new ArrayList<>(n);
        List<String> statuses = new ArrayList<>(n);
        List<List<Float>> embeddings = new ArrayList<>(n);
        String sc = scene != null ? scene : "chat";
        String ly = layer != null ? layer : "body";
        String st = status != null ? status : "active";
        for (ChunkRow row : rows) {
            userIds.add(userId);
            tenantIds.add(tenantId);
            convIds.add(convId);
            msgIds.add(msgId);
            indexes.add((long) row.chunkIndex());
            contents.add(truncate(row.content(), 8192));
            times.add(createdAtMs);
            scenes.add(sc);
            layers.add(ly);
            statuses.add(st);
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
        fields.add(new InsertParam.Field("scene", scenes));
        fields.add(new InsertParam.Field("layer", layers));
        fields.add(new InsertParam.Field("status", statuses));
        fields.add(new InsertParam.Field("embedding", embeddings));
        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
    }

    /** 按场景+层+状态过滤删除（过期清理 / 冲突打标前清）。 */
    public void deleteByFilter(String userId, String tenantId, String scene, String layer, String status) {
        StringBuilder expr = new StringBuilder();
        expr.append("user_id == \"").append(escape(userId)).append('"');
        expr.append(" && tenant_id == \"").append(escape(tenantId)).append('"');
        if (StringUtils.hasText(scene)) {
            expr.append(" && scene == \"").append(escape(scene)).append('"');
        }
        if (StringUtils.hasText(layer)) {
            expr.append(" && layer == \"").append(escape(layer)).append('"');
        }
        if (StringUtils.hasText(status)) {
            expr.append(" && status == \"").append(escape(status)).append('"');
        }
        client.delete(DeleteParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr.toString())
                .build());
    }

    /** L3 定期维护：按 scene+layer 删除 created_at 早于 cutOffMs 的过期向量（§9.2 ② 分层 TTL）。全局维度，不区分 user。 */
    public void deleteExpired(String scene, String layer, long cutOffMs) {
        StringBuilder expr = new StringBuilder();
        if (StringUtils.hasText(scene)) {
            expr.append("scene == \"").append(escape(scene)).append('"');
        }
        if (StringUtils.hasText(layer)) {
            if (expr.length() > 0) {
                expr.append(" && ");
            }
            expr.append("layer == \"").append(escape(layer)).append('"');
        }
        if (expr.length() > 0) {
            expr.append(" && ");
        }
        expr.append("created_at < ").append(cutOffMs);
        client.delete(DeleteParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr.toString())
                .build());
    }

    public List<ChatHistoryHit> search(String userId, String tenantId, List<Float> queryVector, int topK) {
        return search(userId, tenantId, null, queryVector, topK);
    }

    /** convId 非空时按会话过滤（session_search scope=session：仅本会话正文） */
    public List<ChatHistoryHit> search(
            String userId, String tenantId, String convId, List<Float> queryVector, int topK) {
        return search(userId, tenantId, convId, null, null, queryVector, topK);
    }

    /** scene 非空按 scene 过滤；layers 非空按 layer IN (...) 过滤；status 非空按 status 过滤。 */
    public List<ChatHistoryHit> search(
            String userId,
            String tenantId,
            String convId,
            String scene,
            List<String> layers,
            List<Float> queryVector,
            int topK) {
        return search(userId, tenantId, convId, scene, layers, null, queryVector, topK);
    }

    public List<ChatHistoryHit> search(
            String userId,
            String tenantId,
            String convId,
            String scene,
            List<String> layers,
            String status,
            List<Float> queryVector,
            int topK) {
        String expr = buildSearchExpr(userId, tenantId, convId, scene, layers, status);
        return searchWithExpr(userId, tenantId, expr, convId, queryVector, topK);
    }

    /** convIds 非空时按会话列表过滤（session_search scope=workspace 跨会话正文）。 */
    public List<ChatHistoryHit> search(
            String userId,
            String tenantId,
            List<String> convIds,
            String scene,
            List<String> layers,
            List<Float> queryVector,
            int topK) {
        String expr = buildSearchExpr(userId, tenantId, convIds, scene, layers, null);
        return searchWithExpr(userId, tenantId, expr, convIds != null && convIds.size() == 1 ? convIds.get(0) : null,
                queryVector, topK);
    }

    private List<ChatHistoryHit> searchWithExpr(
            String userId,
            String tenantId,
            String expr,
            String convId,
            List<Float> queryVector,
            int topK) {
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
        SearchResultData results = result.getData().getResults();
        // Milvus 空命中时无 FieldData/Scores，SDK 字段读取会抛异常；此处直接返回空列表
        if (results == null || results.getFieldsDataCount() == 0 || results.getScoresCount() == 0) {
            log.debug("[ChatHistory] 检索无命中 conv={}", convId);
            return List.of();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(results);
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
            String hitConvId = fieldAt(convIds, i);
            String msgId = fieldAt(msgIds, i);
            long createdAt = 0L;
            if (createdAts != null && i < createdAts.size() && createdAts.get(i) instanceof Number num) {
                createdAt = num.longValue();
            }
            float score = idScores != null && i < idScores.size() ? idScores.get(i).getScore() : 0f;
            hits.add(new ChatHistoryHit(hitConvId, msgId, content.toString(), score, createdAt));
        }
        return hits;
    }

    /** 去重窗口查询：同 user+tenant+scene+layer 最近 sinceMs 内的向量（按时间倒序 Top-50）。layer 隔离保证
     * 语义提取段 / process 摘要不与 body 原文跨层误判重复。 */
    public List<VectorRow> queryRecentVectors(
            String userId, String tenantId, String scene, String layer, long sinceMs, int limit) {
        String tid = tenantId != null ? tenantId : "default";
        int lim = Math.max(1, Math.min(limit, 500));
        String expr = buildRecentVectorsExpr(userId, tid, scene, layer, sinceMs);
        R<io.milvus.grpc.QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withExpr(expr)
                .withOutFields(List.of("content", "created_at", "embedding"))
                .withLimit((long) lim)
                .build());
        if (response.getData() == null) {
            return List.of();
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<?> contents = wrapper.getFieldWrapper("content").getFieldData();
        List<?> createdAts = wrapper.getFieldWrapper("created_at").getFieldData();
        List<?> embeddings = wrapper.getFieldWrapper("embedding").getFieldData();
        int size = contents != null ? contents.size() : 0;
        List<VectorRow> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object content = contents.get(i);
            if (content == null) {
                continue;
            }
            long createdAt = 0L;
            if (createdAts != null && i < createdAts.size() && createdAts.get(i) instanceof Number num) {
                createdAt = num.longValue();
            }
            List<Float> vector = new ArrayList<>();
            if (embeddings != null && i < embeddings.size() && embeddings.get(i) instanceof List<?> raw) {
                for (Object v : raw) {
                    if (v instanceof Number num) {
                        vector.add(num.floatValue());
                    }
                }
            }
            out.add(new VectorRow(content.toString(), createdAt, vector));
        }
        out.sort(Comparator.comparingLong(VectorRow::createdAtMs).reversed());
        return List.copyOf(out);
    }

    /** 检索 expr：user+tenant 恒过滤；convId 非空会话过滤；scene/layers/status 按需追加。 */
    static String buildSearchExpr(
            String userId, String tenantId, String convId, String scene, List<String> layers, String status) {
        String expr = "user_id == \"" + escape(userId) + "\" && tenant_id == \"" + escape(tenantId) + "\"";
        if (StringUtils.hasText(convId)) {
            expr += " && conv_id == \"" + escape(convId.strip()) + "\"";
        }
        if (StringUtils.hasText(scene)) {
            expr += " && scene == \"" + escape(scene.strip()) + "\"";
        }
        if (layers != null && !layers.isEmpty()) {
            StringBuilder in = new StringBuilder(" && layer IN [");
            for (int i = 0; i < layers.size(); i++) {
                if (i > 0) {
                    in.append(", ");
                }
                in.append('"').append(escape(layers.get(i).strip())).append('"');
            }
            in.append(']');
            expr += in;
        }
        if (StringUtils.hasText(status)) {
            expr += " && status == \"" + escape(status.strip()) + "\"";
        }
        return expr;
    }

    /**
     * 检索 expr：convIds 非空时按会话列表过滤（session_search scope=workspace 跨会话正文）。
     * 与单 convId 版本并存：单值走等值（既有语义），多值走 IN（Milvus 表达式语法）。
     */
    static String buildSearchExpr(
            String userId, String tenantId, List<String> convIds, String scene, List<String> layers, String status) {
        String expr = "user_id == \"" + escape(userId) + "\" && tenant_id == \"" + escape(tenantId) + "\"";
        if (convIds != null && !convIds.isEmpty()) {
            StringBuilder in = new StringBuilder(" && conv_id IN [");
            for (int i = 0; i < convIds.size(); i++) {
                if (i > 0) {
                    in.append(", ");
                }
                in.append('"').append(escape(convIds.get(i).strip())).append('"');
            }
            in.append(']');
            expr += in;
        }
        if (StringUtils.hasText(scene)) {
            expr += " && scene == \"" + escape(scene.strip()) + "\"";
        }
        if (layers != null && !layers.isEmpty()) {
            StringBuilder in = new StringBuilder(" && layer IN [");
            for (int i = 0; i < layers.size(); i++) {
                if (i > 0) {
                    in.append(", ");
                }
                in.append('"').append(escape(layers.get(i).strip())).append('"');
            }
            in.append(']');
            expr += in;
        }
        if (StringUtils.hasText(status)) {
            expr += " && status == \"" + escape(status.strip()) + "\"";
        }
        return expr;
    }

    /** 去重窗口 expr：user+tenant 恒过滤；scene/layer 按需追加（layer 隔离去重）；sinceMs>0 时按时间窗裁剪。 */
    static String buildRecentVectorsExpr(
            String userId, String tenantId, String scene, String layer, long sinceMs) {
        String expr = "user_id == \"" + escape(userId) + "\" && tenant_id == \"" + escape(tenantId) + "\"";
        if (StringUtils.hasText(scene)) {
            expr += " && scene == \"" + escape(scene) + "\"";
        }
        if (StringUtils.hasText(layer)) {
            expr += " && layer == \"" + escape(layer) + "\"";
        }
        if (sinceMs > 0) {
            expr += " && created_at >= " + sinceMs;
        }
        return expr;
    }

    /** 便捷重载：仅 user+tenant+convId（既有调用）。 */
    static String buildSearchExpr(String userId, String tenantId, String convId) {
        return buildSearchExpr(userId, tenantId, convId, null, null, null);
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

    public record VectorRow(String content, long createdAtMs, List<Float> embedding) {
    }

    public record ChatHistoryHit(String convId, String msgId, String content, float score, long createdAtMs) {
    }

    public record ChatHistoryChunk(
            String convId, String msgId, int chunkIndex, String content, long createdAtMs, String layer) {
    }
}
