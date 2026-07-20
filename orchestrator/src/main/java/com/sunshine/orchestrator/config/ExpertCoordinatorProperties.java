package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * agent.expert 配置壳（提示词已迁 Catalog expert.*）。
 * 保留 Bean 以免历史 Nacos 键绑定失败；无业务字段。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.expert")
public class ExpertCoordinatorProperties {
}
