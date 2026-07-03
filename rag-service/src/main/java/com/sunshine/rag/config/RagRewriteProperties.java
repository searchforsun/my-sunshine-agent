package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/** RAG 检索链路 Query 改写；业务 prompt SSOT 为 kb DB bundle，此处仅 timeline 等运维项 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "rag.rewrite")
public class RagRewriteProperties {
    private Rag rag = new Rag();
    private EmptyRecall emptyRecall = new EmptyRecall();
    private Timeline timeline = defaultTimeline();

    private static Timeline defaultTimeline() {
        Timeline t = new Timeline();
        t.setRag("优化检索词");
        t.setHyde("生成参考文档");
        t.setEmptyRecall("换种方式再查");
        return t;
    }

    @Data
    public static class Rag {
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private String systemPrompt = "";
        private Hyde hyde = new Hyde();
    }

    @Data
    public static class Hyde {
        private boolean enabled = false;
        private String model = "deepseek-v4-flash";
        private int maxChars = 480;
        private String systemPrompt = "";
    }

    @Data
    public static class EmptyRecall {
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private int maxAlternatives = 2;
        private String systemPrompt = "";
    }

    @Data
    public static class Timeline {
        private String rag = "";
        private String hyde = "";
        private String emptyRecall = "";

        public String labelFor(String scenario) {
            if (scenario == null) {
                return "";
            }
            return switch (scenario) {
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
