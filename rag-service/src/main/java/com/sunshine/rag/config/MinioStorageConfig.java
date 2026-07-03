package com.sunshine.rag.config;

import com.sunshine.rag.storage.MinioStorageService;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioStorageConfig {

    private final RagStorageProperties storageProperties;

    @Bean
    MinioClient ragMinioClient() {
        RagStorageProperties.Minio minio = storageProperties.getMinio();
        log.info("[RagStorage] MinIO endpoint={} bucket={}", minio.getEndpoint(), minio.getBucket());
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    @Bean
    ApplicationRunner ragMinioBucketInit(MinioStorageService minioStorageService) {
        return args -> minioStorageService.ensureBucket();
    }
}
