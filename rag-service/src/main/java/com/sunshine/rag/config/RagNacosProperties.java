package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Nacos Open API 发布凭证（spec §6.4） */
@Data
@Component
@ConfigurationProperties(prefix = "rag.nacos")
public class RagNacosProperties {

    private String serverAddr = "http://127.0.0.1:8848/nacos";
    private String username = "nacos";
    private String password = "nacos";
    private String group = "DEFAULT_GROUP";
    private String tenant = "";
    /** 非空时将 publish 后的 YAML 写回本地目录（如 docs/nacos） */
    private String exportDir = "";
}
