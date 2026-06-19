package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Nacos agent.routing.rules — 规则硬路由 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.routing")
public class RoutingRuleProperties {

    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {
        private String id;
        private int priority = 0;
        /** any | all */
        private String match = "any";
        private List<String> patterns = new ArrayList<>();
        private PlanSpec plan = new PlanSpec();
    }

    @Data
    public static class PlanSpec {
        private String mode = "workflow";
        private String workflowId;
        private Map<String, String> params = new LinkedHashMap<>();
    }
}
