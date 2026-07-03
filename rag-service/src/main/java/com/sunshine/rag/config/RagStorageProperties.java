package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.storage")
public class RagStorageProperties {
    /** local | minio */
    private String type = "minio";
    private Minio minio = new Minio();
    /** 本地回退目录（type=local 或 MinIO 不可用时 dev 导出） */
    private String localReportDir = "reports/rag/eval-reports";

    @Data
    public static class Minio {
        private String endpoint = "http://ecs4c16g:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin123";
        private String bucket = "sunshine-rag";
    }

    public boolean isMinio() {
        return "minio".equalsIgnoreCase(type);
    }
}
