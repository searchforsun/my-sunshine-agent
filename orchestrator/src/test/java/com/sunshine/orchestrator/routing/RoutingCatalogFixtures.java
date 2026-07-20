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

    public static final String STRUCTURAL_ID = "routing-rule.structural-plan";
    public static final String PEER_ID = "routing-rule.peer-phrase";
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
        entries.add(entry(STRUCTURAL_ID, "多步跨域→Plan", 100,
                        "{\"matchType\":\"structural\",\"minDomainGroups\":2,"
                                + "\"patterns\":[\"先.+再\",\"再.+(并|然后|接着)\",\"分步\",\"多步\","
                                + "\"并对.+?(分析|审查|检查|评估)\",\"完整处理\",\"一套.+(分析|流程|处理)\"],"
                                + "\"domainGroups\":{\"knowledge\":[\"制度\",\"检索\",\"知识库\",\"政策\",\"差旅办法\",\"报销规定\"],"
                                + "\"finance\":[\"待审批\",\"报销\",\"财务\",\"付款\",\"单据\"],"
                                + "\"analysis\":[\"合规\",\"分析\",\"审查\",\"对比\",\"评估\",\"结论\"]},"
                                + "\"plan\":{\"mode\":\"plan-workflow\",\"params\":{}}}"));
        entries.add(entry(PEER_ID, "Peer句式→协作", 90,
                        "{\"matchType\":\"peer_phrase\","
                                + "\"patterns\":[\"互相验证\",\"交叉审查\",\"多专家讨论\",\"分别分析并质疑\","
                                + "\"两个角度.*审查\",\"专家.*分别.*审查\"],"
                                + "\"plan\":{\"mode\":\"peer-collab\",\"params\":{}}}"));
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

    /** T8：PromptComposer / ReAct 单测与集成测试最小正文（对齐 seed-prompts 关键 id） */
    private static List<PromptCatalogEntry> promptTextSeeds() {
        return List.of(
                text("system-prompt", "system", "你是 Sunshine AI 测试助手。"),
                text("mode-overlay.react", "mode-overlay", "react-mode-minimal"),
                text("mode-overlay.react-restart", "mode-overlay", ""),
                text("mode-overlay.direct", "mode-overlay", ""),
                text("mode-overlay.workflow", "mode-overlay", ""),
                text("mode-overlay.subagent", "mode-overlay", ""),
                text("memory.layer-prompt", "memory", "memory-layer-prompt"),
                text("scope-prompt", "scope", ""),
                text("hitl.agent-prompt", "hitl", ""));
    }

    private static PromptCatalogEntry text(String id, String kind, String contentText) {
        return new PromptCatalogEntry(id, kind, id, true, 0, 1, contentText, null);
    }

    private static PromptCatalogEntry entry(String id, String displayName, int priority, String contentJson) {
        return new PromptCatalogEntry(id, "routing-rule", displayName, true, priority, 1, null, contentJson);
    }
}
