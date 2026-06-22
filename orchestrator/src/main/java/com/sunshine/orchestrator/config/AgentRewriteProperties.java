package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Query 改写配置（Task 3.8.1：rag / intent / empty-recall）。
 * 提示词 SSOT 见 Nacos {@code sunshine-orchestrator.yaml}（本地副本 docs/nacos/）。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.rewrite")
public class AgentRewriteProperties {

    private Rag rag = new Rag();
    private Intent intent = new Intent();
    private EmptyRecall emptyRecall = new EmptyRecall();
    /** 时间线展开区：各改写场景的时机说明（SSOT 见 Nacos agent.rewrite.timeline） */
    private Timeline timeline = new Timeline();

    @Data
    public static class Rag {
        /** 进入 RAG 检索前改写；Nacos 默认开启 */
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        /** RAG 检索前改写 system 提示词 */
        private String systemPrompt = "";
        /** HyDE：生成假想制度片段再检索（默认关闭，Nacos agent.rewrite.rag.hyde.enabled） */
        private Hyde hyde = new Hyde();
    }

    @Data
    public static class Hyde {
        private boolean enabled = false;
        private String model = "deepseek-v4-flash";
        /** 假想文档最大字符数（检索 query 截断） */
        private int maxChars = 480;
        private String systemPrompt = "";
    }

    @Data
    public static class Intent {
        /** 规则未命中且短 query 时补全意图；Nacos 默认开启 */
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        /** 低于此字数（不含）才触发 intent 改写 */
        private int maxChars = 8;
        private String systemPrompt = "";
    }

    @Data
    public static class EmptyRecall {
        /** RAG 首次 0 命中二次检索；Nacos 默认开启 */
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private int maxAlternatives = 2;
        /** 支持 {@code %d} 占位符，运行时注入 {@link #maxAlternatives} */
        private String systemPrompt = "";
    }

    @Data
    public static class Timeline {
        /** 规则未命中且问句过短时，意图分类前补全 */
        private String intent = "";
        /** 进入向量/混合检索前，优化检索 query */
        private String rag = "";
        /** HyDE：生成假想制度片段作为检索 query */
        private String hyde = "";
        /** 首次零命中后，生成替代 query 二次检索 */
        private String emptyRecall = "";

        public String labelFor(String scenario) {
            if (scenario == null) {
                return "";
            }
            return switch (scenario) {
                case "intent" -> intent;
                case "rag" -> rag;
                case "hyde" -> hyde;
                case "empty-recall" -> emptyRecall;
                default -> "";
            };
        }
    }

    public Timeline timelineOrDefault() {
        return timeline != null ? timeline : new Timeline();
    }
}
