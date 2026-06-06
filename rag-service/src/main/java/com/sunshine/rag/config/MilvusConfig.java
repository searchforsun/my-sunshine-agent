package com.sunshine.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库连接配置
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host:8.140.48.6}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.database:default}")
    private String database;

    @Bean
    public MilvusServiceClient milvusClient() {
        log.info("[RAG] 连接 Milvus: {}:{}, db={}", host, port, database);
        ConnectParam param = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withDatabaseName(database)
                .build();
        return new MilvusServiceClient(param);
    }
}
