package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绑定 Nacos sunshine-workflows.yaml — workflow 目录与图定义
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "workflow")
public class WorkflowProperties {

    private List<CatalogEntry> catalog = new ArrayList<>();
    private Map<String, WorkflowDefinitionProps> definitions = new LinkedHashMap<>();

    @Data
    public static class CatalogEntry {
        private String id;
        private String mode;
        private String desc;
        /** 用户可见工作流名，缺省用 desc */
        private String displayName;
        /** 可选：覆盖 agent.timeline.intent.modes.workflow.after，占位符同 ModeIntent */
        private String intentAfter;
        private List<String> nodes = new ArrayList<>();
        private List<String> examples = new ArrayList<>();
    }

    @Data
    public static class WorkflowDefinitionProps {
        private List<NodeProps> nodes = new ArrayList<>();
        private List<String> edges = new ArrayList<>();
    }

    @Data
    public static class NodeProps {
        private String id;
        private String type;
        /** 节点中文展示名，缺省按 type / 绑定工具推导 */
        private String displayName;
        private Map<String, Object> params = new LinkedHashMap<>();
    }
}
