package com.sunshine.tool.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tool")
@Getter
@Setter
public class ToolIntegrationProperties {

    private Sdk sdk = new Sdk();
    private Mcp mcp = new Mcp();

    @Getter
    @Setter
    public static class Sdk {
        private int pullIntervalSeconds = 60;
        private int invokeTimeoutSeconds = 30;
    }

    @Getter
    @Setter
    public static class Mcp {
        private int refreshIntervalSeconds = 300;
        private int invokeTimeoutSeconds = 60;
    }
}
