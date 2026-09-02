package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.desensitize")
public class RagDesensitizeProperties {
    private boolean enabled = true;
    /** 脱敏失败时是否阻断发布 */
    private boolean failOnError = true;
}
