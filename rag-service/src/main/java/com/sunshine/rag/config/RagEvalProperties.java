package com.sunshine.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.eval")
public class RagEvalProperties {

    /** 无历史 passed_gate 报告时的 Recall@5 基线 */
    private double defaultBaselineRecallAt5 = 0.98;
    /** 评测报告输出目录 */
    private String reportDir = "reports/rag/eval-reports";
    /** Suggest 调优 */
    private Suggest suggest = new Suggest();

    @Data
    public static class Suggest {
        private String model = "deepseek-v4-flash";
        /** SSOT：docs/nacos/sunshine-rag.yaml → rag.eval.suggest.system-prompt */
        private String systemPrompt = "";
    }
}
