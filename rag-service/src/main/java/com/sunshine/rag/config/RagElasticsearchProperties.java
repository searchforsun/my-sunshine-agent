package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.elasticsearch")
public class RagElasticsearchProperties {

    private boolean enabled = true;
    private String url = "http://ecs4c16g:9200";
    private String index = "sunshine_rag_chunks";
}
