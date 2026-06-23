package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Nacos agent.routing — 黄金规则 + 结构守卫（L1/L2） */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.routing")
public class RoutingRuleProperties {

    private Structural structural = new Structural();
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Structural {
        private boolean enabled = true;
        /** 至少命中几个 domain-groups 才视为跨领域多步 */
        private int minDomainGroups = 2;
        private List<String> multiStepPatterns = defaultMultiStepPatterns();
        /** group 名仅作配置标识；命中任一组内关键词计 1 分 */
        private Map<String, List<String>> domainGroups = defaultDomainGroups();

        private static List<String> defaultMultiStepPatterns() {
            return new ArrayList<>(List.of(
                    "先.+再",
                    "再.+(并|然后|接着)",
                    "分步",
                    "多步",
                    "并对.+?(分析|审查|检查|评估)",
                    "完整处理",
                    "一套.+(分析|流程|处理)"));
        }

        private static Map<String, List<String>> defaultDomainGroups() {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            groups.put("knowledge", List.of("制度", "检索", "知识库", "政策", "差旅办法", "报销规定"));
            groups.put("finance", List.of("待审批", "报销", "财务", "付款", "单据"));
            groups.put("analysis", List.of("合规", "分析", "审查", "对比", "评估", "结论"));
            return groups;
        }
    }

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
