package com.sunshine.chatimage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 聊天图片 MinIO 存储配置 */
@Data
@ConfigurationProperties(prefix = "chat-image")
public class ChatImageProperties {
    private Minio minio = new Minio();
    /** 允许的图片扩展名（小写，含点） */
    private String allowedExtensions = ".png,.jpg,.jpeg,.webp,.gif";
    /** 单张上限（字节） */
    private long maxBytes = 10 * 1024 * 1024;

    @Data
    public static class Minio {
        private String endpoint = "http://ecs4c16g:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin123";
        private String bucket = "sunshine-chat-images";
        /** 对外可访问基址（模型/浏览器拉取），缺省同 endpoint */
        private String publicEndpoint = "";
    }
}
