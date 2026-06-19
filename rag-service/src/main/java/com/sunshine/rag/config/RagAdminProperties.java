package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** RAG Admin API 配置 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag.admin")
public class RagAdminProperties {
    /** 为空时不校验 token（仅内网 MVP） */
    private String token = "";
}
