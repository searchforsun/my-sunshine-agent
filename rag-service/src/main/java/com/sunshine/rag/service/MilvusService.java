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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 向量数据库操作服务
 * MilvusServiceClient 注入为 optional，Milvus 不可用时服务正常启动
 */
@Slf4j
@Service
public class MilvusService {

    @Autowired(required = false)
    private MilvusServiceClient client;

    private static final String COLLECTION = "sunshine_knowledge";
    private static final int DIMENSION = 1024;

    @PostConstruct
    public void init() {
        if (client == null) {
            log.warn("[RAG] Milvus 不可用，知识库功能降级");
            return;
        }
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("[RAG] Collection 初始化失败: {}", e.getMessage());
        }
    }

    private void ensureCollection() {
        R<Boolean> exists = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());

        if (Boolean.TRUE.equals(exists.getData())) {
            log.info("[RAG] Collection '{}' 已存在", COLLECTION);
            return;
        }

        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Sunshine AI 知识库")
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build())
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

        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());

        log.info("[RAG] Collection '{}' 创建完成", COLLECTION);
    }

    public void insert(String content, List<Float> embedding) {
        if (client == null) {
            log.warn("[RAG] Milvus 不可用，跳过插入");
            return;
        }
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("content", List.of(content)));
        fields.add(new InsertParam.Field("embedding", List.of(embedding)));

        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
    }

    public List<String> search(List<Float> queryVector, int topK) {
        if (client == null) {
            log.warn("[RAG] Milvus 不可用，返回空检索结果");
            return List.of();
        }
        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withOutFields(List.of("content"))
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .build());

        if (result.getData() == null) {
            log.warn("[RAG] 检索返回空: {}", result.getMessage());
            return List.of();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(
                result.getData().getResults());

        List<String> results = new ArrayList<>();
        List<?> contentList = wrapper.getFieldData("content", 0);
        for (Object obj : contentList) {
            if (obj != null) {
                results.add(obj.toString());
            }
        }

        log.info("[RAG] 检索完成: topK={}, 命中={}", topK, results.size());
        return results;
    }
}
