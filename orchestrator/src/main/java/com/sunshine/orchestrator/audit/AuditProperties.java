package com.sunshine.orchestrator.audit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sunshine.audit")
public class AuditProperties {

    private boolean enabled = true;
    private String topic = "sunshine-audit";
    private String consumerGroup = "sunshine-audit-consumer";
    private Elasticsearch elasticsearch = new Elasticsearch();

    @Data
    public static class Elasticsearch {
        private boolean enabled = true;
        private String url = "http://ecs4c16g:9200";
        private String index = "sunshine-audit";
    }
}
