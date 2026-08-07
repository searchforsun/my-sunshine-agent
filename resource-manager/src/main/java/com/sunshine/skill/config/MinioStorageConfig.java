package com.sunshine.skill.config;

import com.sunshine.skill.storage.MinioSkillPackageStorage;
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
@ConditionalOnProperty(name = "skill.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioStorageConfig {

    private final SkillStorageProperties storageProperties;

    @Bean
    MinioClient skillMinioClient() {
        SkillStorageProperties.Minio minio = storageProperties.getStorage().getMinio();
        log.info("[SkillStorage] MinIO endpoint={} bucket={}", minio.getEndpoint(), minio.getBucket());
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    @Bean
    ApplicationRunner skillMinioBucketInit(MinioSkillPackageStorage minioStorage) {
        return args -> minioStorage.ensureBucket();
    }
}
