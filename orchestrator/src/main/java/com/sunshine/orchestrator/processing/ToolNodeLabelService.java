package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/** ReAct 工具步与 Workflow node 步时间线文案 — SSOT：Nacos agent.timeline.steps.tool / node */
@Service
@RefreshScope
@RequiredArgsConstructor
public class ToolNodeLabelService {

    private final TimelinePromptCatalog timelinePromptCatalog;
    private final ToolCatalogService toolCatalogService;

    @PostConstruct
    void init() {
        ToolNodeLabels.bind(this);
    }

    public String toolDisplayName(String stepId) {
        if (stepId == null) {
            return "";
        }
        String toolName = ToolStepIds.catalogToolName(stepId);
        if (toolName != null) {
            return toolCatalogService.displayName(toolName);
        }
        return stepId;
    }

    public String toolLabel(String stepId) {
        return applyTemplate(toolTemplate().getLabel(), vars(null, toolDisplayName(stepId)));
    }

    public String toolBefore(String stepId) {
        return applyTemplate(toolTemplate().getBefore(), vars(null, toolDisplayName(stepId)));
    }

    public String toolActive(String stepId) {
        return applyTemplate(toolTemplate().getActive(), vars(null, toolDisplayName(stepId)));
    }

    public String toolAfter(String stepId, String detail) {
        // detail 仅来自 summarize-output 的结构化摘要；truncate 为 null 时走 Nacos 模板
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return applyTemplate(toolTemplate().getAfter(), vars(null, toolDisplayName(stepId)));
    }

    public String nodeBefore(String stepId, String clippedQuery, String displayNameOverride) {
        String displayName = resolveNodeDisplayName(stepId, displayNameOverride);
        if (StringUtils.hasText(clippedQuery)) {
            String withQuery = nodeTemplate().getBeforeWithQuery();
            if (StringUtils.hasText(withQuery)) {
                return applyTemplate(withQuery, vars(clippedQuery.strip(), displayName));
            }
        }
        return applyTemplate(nodeTemplate().getBefore(), vars(null, displayName));
    }

    public String nodeActive(String stepId, String displayNameOverride) {
        return applyTemplate(nodeTemplate().getActive(), vars(null, resolveNodeDisplayName(stepId, displayNameOverride)));
    }

    public String nodeAfter(String stepId, String detail, String displayNameOverride) {
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return applyTemplate(nodeTemplate().getAfter(), vars(null, resolveNodeDisplayName(stepId, displayNameOverride)));
    }

    private String resolveNodeDisplayName(String stepId, String displayNameOverride) {
        if (StringUtils.hasText(displayNameOverride)) {
            return displayNameOverride.strip();
        }
        return WorkflowNodeLabels.displayNameByStepId(stepId);
    }

    private AgentPromptProperties.StepTimeline toolTemplate() {
        return stepTemplate(TimelineStepId.TOOL.id(), defaultToolTemplate());
    }

    private AgentPromptProperties.StepTimeline nodeTemplate() {
        return stepTemplate(TimelineStepId.NODE.id(), defaultNodeTemplate());
    }

    private AgentPromptProperties.StepTimeline stepTemplate(String key, AgentPromptProperties.StepTimeline fallback) {
        var steps = timelinePromptCatalog.steps();
        if (steps == null) {
            return fallback;
        }
        AgentPromptProperties.StepTimeline step = steps.get(key);
        return step != null ? step : fallback;
    }

    private static AgentPromptProperties.StepTimeline defaultToolTemplate() {
        var tool = new AgentPromptProperties.StepTimeline();
        tool.setLabel("调用工具 {displayName}");
        tool.setBefore("准备{displayName}");
        tool.setActive("正在{displayName}");
        tool.setAfter("{displayName}完成");
        return tool;
    }

    private static AgentPromptProperties.StepTimeline defaultNodeTemplate() {
        var node = new AgentPromptProperties.StepTimeline();
        node.setBefore("准备{displayName}");
        node.setActive("正在{displayName}");
        node.setAfter("{displayName}完成");
        node.setBeforeWithQuery("准备处理{query}的「{displayName}」环节");
        return node;
    }

    private static Map<String, String> vars(String clippedQuery, String displayName) {
        Map<String, String> map = new HashMap<>();
        map.put("query", clippedQuery != null ? clippedQuery : "");
        map.put("displayName", displayName != null ? displayName : "");
        return map;
    }

    private static String applyTemplate(String template, Map<String, String> vars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        String result = template.strip();
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
