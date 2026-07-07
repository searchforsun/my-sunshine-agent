package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.ToolSummarizeOutputResponse;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeCompletionLabelService;
import com.sunshine.orchestrator.execution.WorkflowNodeCompletionLabels;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 单测绑定 Nacos 默认时间线模板，替代已删除的 IntentLabels/TimelineLabels fallback */
public final class TimelineLabelTestSupport {

    private static final Pattern HIT_COUNT = Pattern.compile("共\\s*(\\d+)\\s*条");
    private static final Pattern NO_HIT_HEADER = Pattern.compile("^未找到相关知识库");
    private static final Pattern SOURCE_DOCS = Pattern.compile("来源文档[：:]\\s*([^\\n【]+)");

    private TimelineLabelTestSupport() {
    }

    public static ToolCatalogService bindDefaults() {
        AgentPromptProperties agentProps = new AgentPromptProperties();
        WorkflowProperties workflowProps = new WorkflowProperties();
        workflowProps.setDefinitions(new LinkedHashMap<>());
        SkillCatalogService skillCatalog = Mockito.mock(SkillCatalogService.class);
        ToolCatalogService toolCatalog = Mockito.mock(ToolCatalogService.class);
        stubDefaultSummarize(toolCatalog);
        WorkflowNodeLabelService workflowLabels = new WorkflowNodeLabelService(workflowProps, toolCatalog, agentProps);
        WorkflowNodeLabels.bind(workflowLabels);
        WorkflowNodeCompletionLabels.bind(new WorkflowNodeCompletionLabelService(agentProps));
        StepLabels.bind(toolCatalog);
        IntentLabelService labelService = new IntentLabelService(
                agentProps,
                workflowProps,
                workflowLabels);
        SkillLoadLabels.bind(new SkillLoadLabelService(skillCatalog, agentProps));
        PlanApprovalLabels.bind(new PlanApprovalLabelService(agentProps));
        ToolNodeLabels.bind(new ToolNodeLabelService(agentProps, toolCatalog));
        HitlLabels.bind(new HitlLabelService(agentProps));
        SummaryStepLabels.bind(new SummaryStepLabelService(agentProps, toolCatalog));
        IntentLabels.bind(labelService);
        TimelineLabels.bind(labelService);
        TimelineStepLabels.bind(labelService);
        ThinkStepLabels.bind(labelService);
        return toolCatalog;
    }

    /** 单测本地模拟 tool-manager summarize-output（与 Nacos tool.timeline.result 默认模板一致） */
    public static void stubDefaultSummarize(ToolCatalogService toolCatalog) {
        lenient().when(toolCatalog.summarizeOutputDetail(eq("search_knowledge"), anyString()))
                .thenAnswer(inv -> summarizeSearchKnowledge(inv.getArgument(1)));
        lenient().when(toolCatalog.summarizeOutput(eq("search_knowledge"), anyString()))
                .thenAnswer(inv -> summarizeSearchKnowledge(inv.getArgument(1)).summary());
        lenient().when(toolCatalog.summarizeOutput(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String tool = inv.getArgument(0);
                    String text = inv.getArgument(1);
                    if ("search_knowledge".equals(tool)) {
                        return summarizeSearchKnowledge(text).summary();
                    }
                    return text != null ? text.strip() : "";
                });
    }

    private static ToolSummarizeOutputResponse summarizeSearchKnowledge(String text) {
        if (text == null || text.isBlank()) {
            return new ToolSummarizeOutputResponse("命中 0 条", true, true);
        }
        if (NO_HIT_HEADER.matcher(text.strip()).find()) {
            return new ToolSummarizeOutputResponse("命中 0 条", true, false);
        }
        Matcher countMatcher = HIT_COUNT.matcher(text);
        if (!countMatcher.find()) {
            if (text.strip().matches("命中\\s*\\d+\\s*条.*")) {
                Matcher legacy = Pattern.compile("命中\\s*(\\d+)\\s*条").matcher(text.strip());
                if (legacy.find()) {
                    String count = legacy.group(1);
                    boolean zero = "0".equals(count);
                    return new ToolSummarizeOutputResponse(text.strip(), zero, zero);
                }
            }
            return new ToolSummarizeOutputResponse("命中 0 条", true, false);
        }
        String count = countMatcher.group(1);
        Matcher docMatcher = SOURCE_DOCS.matcher(text);
        if (docMatcher.find()) {
            String docNames = docMatcher.group(1).trim();
            if (!docNames.isEmpty()) {
                String summary = "命中 " + count + " 条，来源：" + docNames;
                return new ToolSummarizeOutputResponse(summary, "0".equals(count), false);
            }
        }
        String summary = "命中 " + count + " 条";
        return new ToolSummarizeOutputResponse(summary, "0".equals(count), false);
    }

    public static void unbind() {
        IntentLabels.bind(null);
        TimelineLabels.bind(null);
        TimelineStepLabels.bind(null);
        ThinkStepLabels.bind(null);
        SkillLoadLabels.bind(null);
        PlanApprovalLabels.bind(null);
        ToolNodeLabels.bind(null);
        HitlLabels.bind(null);
        SummaryStepLabels.bind(null);
        WorkflowNodeLabels.bind(null);
        WorkflowNodeCompletionLabels.bind(null);
        StepLabels.bind(null);
    }
}
