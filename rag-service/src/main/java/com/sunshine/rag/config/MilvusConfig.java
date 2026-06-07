package com.sunshine.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 向量数据库连接配置
 * 连接失败时返回 null，服务仍可启动（知识库功能降级）
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
                .withConnectTimeout(3, TimeUnit.SECONDS)
                .build();
        try {
            return new MilvusServiceClient(param);
        } catch (RuntimeException e) {
            log.warn("[RAG] Milvus 不可用，知识库功能降级: {}", e.getMessage());
            return null;
        }
    }
}
