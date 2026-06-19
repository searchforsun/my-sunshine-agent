package com.sunshine.rag.service;

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
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 向量数据库操作服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient client;

    private static final String COLLECTION = "sunshine_knowledge";
    private static final int DIMENSION = 1024;

    @PostConstruct
    public void init() {
        ensureCollection();
    }

    private void ensureCollection() {
        R<Boolean> exists = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());

        if (Boolean.TRUE.equals(exists.getData())) {
            if (hasDocNameField()) {
                loadCollection();
                log.info("[RAG] Collection '{}' 已存在且 schema 匹配", COLLECTION);
                return;
            }
            log.warn("[RAG] Collection '{}' schema 过旧，重建以支持 doc_name", COLLECTION);
            dropCollection();
        }

        createCollection();
    }

    private boolean hasDocNameField() {
        R<io.milvus.grpc.DescribeCollectionResponse> response = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
        if (response.getData() == null) {
            return false;
        }
        DescCollResponseWrapper wrapper = new DescCollResponseWrapper(response.getData());
        return wrapper.getFields().stream().anyMatch(field -> "doc_name".equals(field.getName()));
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
                .withDescription("Sunshine AI 知识库")
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("doc_name")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(512)
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

        loadCollection();
        log.info("[RAG] Collection '{}' 创建完成", COLLECTION);
    }

    private void loadCollection() {
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .build());
    }

    public void insert(String docName, String content, List<Float> embedding) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("doc_name", List.of(docName)));
        fields.add(new InsertParam.Field("content", List.of(content)));
        fields.add(new InsertParam.Field("embedding", List.of(embedding)));

        client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
    }

    public List<SearchHit> search(List<Float> queryVector, int topK) {
        R<SearchResults> result = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withOutFields(List.of("doc_name", "content"))
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

        log.info("[RAG] 检索完成: topK={}, 返回={}", topK, results.size());
        return results;
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
}
