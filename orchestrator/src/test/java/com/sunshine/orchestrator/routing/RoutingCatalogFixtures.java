package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 docker/mysql/init/17-sunshine-prompt-manager.sql 五条 routing-rule 种子。
 */
public final class RoutingCatalogFixtures {

    public static final String REACT_POLICY_QA_ID = "routing-rule.react-policy-qa";
    public static final String FINANCE_SMART_ID = "routing-rule.rule-finance-smart-compliance";
    public static final String KNOWLEDGE_BUDGET_ID = "routing-rule.rule-knowledge-budget-travel";
    public static final String FINANCE_LIST_ID = "routing-rule.rule-finance-list-pending";

    private RoutingCatalogFixtures() {}

    public static PromptCatalogSnapshot seedSnapshot() {
        return PromptCatalogSnapshot.of(1L, seedEntries());
    }

    public static PromptCatalogHolder seedHolder() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        holder.replace(seedSnapshot());
        return holder;
    }

    public static List<PromptCatalogEntry> seedEntries() {
        List<PromptCatalogEntry> entries = new ArrayList<>();
        entries.addAll(promptTextSeeds());
        entries.add(entry(REACT_POLICY_QA_ID, "制度咨询→绑定政策技能", 40,
                        "{\"matchType\":\"regex\",\"match\":\"any\","
                                + "\"patterns\":[\"制度怎么说\",\"有没有规定\",\"差旅办法\",\"报销规定\","
                                + "\"考勤制度\",\"人事制度\",\"能不能报(?!销进度)\",\"政策.*怎么规定\"],"
                                + "\"plan\":{\"mode\":\"fast\",\"params\":{\"skill\":\"policy-qa\"}}}"));
        entries.add(entry(FINANCE_SMART_ID, "财务合规→finance-smart", 20,
                        "{\"matchType\":\"regex\",\"match\":\"any\","
                                + "\"patterns\":[\"是否合规\",\"合规吗\",\"合不合规\",\"对比制度\"],"
                                + "\"plan\":{\"mode\":\"workflow\",\"workflowId\":\"finance-smart\","
                                + "\"params\":{\"status\":\"pending\"}}}"));
        entries.add(entry(KNOWLEDGE_BUDGET_ID, "预算出差→knowledge-qa", 15,
                        "{\"matchType\":\"regex\",\"match\":\"any\","
                                + "\"patterns\":[\"预算.*出差\",\"出差.*预算\",\"预算超支\",\"预算不够.*出差\"],"
                                + "\"plan\":{\"mode\":\"workflow\",\"workflowId\":\"knowledge-qa\",\"params\":{}}}"));
        entries.add(entry(FINANCE_LIST_ID, "待审批列表→finance-list", 10,
                        "{\"matchType\":\"regex\",\"match\":\"any\","
                                + "\"patterns\":[\"有哪些待审批\",\"查询待审批\",\"列出待审批\","
                                + "\"待审批的.*报销\",\"待审批.*付款\"],"
                                + "\"plan\":{\"mode\":\"workflow\",\"workflowId\":\"finance-list\","
                                + "\"params\":{\"status\":\"pending\"}}}"));
        return entries;
    }

    /** T8/T9：PromptComposer / Intent / Rewrite / Answer / Timeline 单测最小正文 */
    private static List<PromptCatalogEntry> promptTextSeeds() {
        return List.of(
                text("system-prompt", "system", "你是 Sunshine AI 测试助手。"),
                text("mode-overlay.react", "mode-overlay", "react-mode-minimal"),
                text("mode-overlay.react-restart", "mode-overlay", ""),
                text("mode-overlay.direct", "mode-overlay", ""),
                text("mode-overlay.workflow", "mode-overlay", ""),
                text("mode-overlay.subagent", "mode-overlay", ""),
                text("context.layer-prompt", "context", "context-layer-prompt"),
                text("context.usage-rules", "context", "context-usage-rules"),
                text("context.current-user-marker", "context", "【当前提问 · 仅此作答】"),
                text("scope-prompt", "scope", ""),
                text("hitl.agent-prompt", "hitl", ""),
                text("intent.classifier", "intent", "classifier-stub"),
                text("answer.template", "answer",
                        "用户问题：{{start.userQuery}}\n\n上游数据：\n{{plan.upstream}}\n\n请汇总。"),
                text("answer.overlay", "answer", ""),
                text("rewrite.intent", "rewrite", "rewrite-intent-stub"),
                text("rewrite.planner", "rewrite", "rewrite-planner-stub"),
                json("rewrite.timeline", "rewrite",
                        "{\"intent\":\"补全问句\",\"planner\":\"优化规划输入\"}"),
                json("timeline.intent", "timeline",
                        "{\"label\":\"识别意图\",\"before\":\"识别用户意图\","
                                + "\"active\":\"正在识别用户意图\","
                                + "\"default-after\":\"已完成意图识别\"}"));
    }

    private static PromptCatalogEntry text(String id, String kind, String contentText) {
        return new PromptCatalogEntry(id, kind, id, true, 0, 1, contentText, null);
    }

    private static PromptCatalogEntry json(String id, String kind, String contentJson) {
        return new PromptCatalogEntry(id, kind, id, true, 0, 1, null, contentJson);
    }

    private static PromptCatalogEntry entry(String id, String displayName, int priority, String contentJson) {
        return new PromptCatalogEntry(id, "routing-rule", displayName, true, priority, 1, null, contentJson);
    }
}
