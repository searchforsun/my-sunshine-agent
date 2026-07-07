package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 意图步骤 detail / after 文案 — 从 Nacos agent.timeline.intent + workflow catalog 解析。
 * before / active 见 {@link TimelineStepLabelService}；think 见 {@link ThinkStepLabelService}。
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class IntentLabelService {

    private final AgentPromptProperties agentPromptProperties;
    private final WorkflowProperties workflowProperties;
    private final WorkflowNodeLabelService workflowNodeLabelService;

    @PostConstruct
    void init() {
        IntentLabels.bind(this);
    }

    public String intentDetail(ExecutionPlan plan) {
        if (plan == null) {
            return modeDetail(modeConfig(ExecutionMode.SIMPLE_LLM), "简单对话");
        }
        return switch (plan.mode()) {
            case SIMPLE_LLM -> modeDetail(modeConfig(ExecutionMode.SIMPLE_LLM), "简单对话");
            case REACT -> modeDetail(modeConfig(ExecutionMode.REACT), "自主智能体");
            case PLAN_WORKFLOW -> modeDetail(modeConfig(ExecutionMode.PLAN_WORKFLOW), "动态规划");
            case PEER_COLLAB -> modeDetail(modeConfig(ExecutionMode.PEER_COLLAB), "多专家协作");
            case WORKFLOW -> workflowNodeLabelService.workflowDisplayName(plan.workflowId());
        };
    }

    public String intentAfterSummary(String clippedQuery, String detail) {
        AgentPromptProperties.IntentTimeline cfg = agentPromptProperties.intentTimelineOrDefault();
        if (!StringUtils.hasText(detail)) {
            return TimelineLabelTemplates.applyTemplate(cfg.getDefaultAfter(),
                    TimelineLabelTemplates.vars(clippedQuery, detail, null, null));
        }
        WorkflowProperties.CatalogEntry catalogEntry = findCatalogByDetail(detail);
        if (catalogEntry != null) {
            String template = StringUtils.hasText(catalogEntry.getIntentAfter())
                    ? catalogEntry.getIntentAfter()
                    : modeAfter(modeConfig(ExecutionMode.WORKFLOW), "{query}将按「{displayName}」流程处理");
            return TimelineLabelTemplates.applyTemplate(template, TimelineLabelTemplates.vars(
                    clippedQuery, detail, catalogEntry.getId(), displayNameOf(catalogEntry)));
        }
        AgentPromptProperties.ModeIntent mode = findModeByDetail(detail);
        if (mode != null && StringUtils.hasText(mode.getAfter())) {
            return TimelineLabelTemplates.applyTemplate(mode.getAfter(),
                    TimelineLabelTemplates.vars(clippedQuery, detail, null, detail));
        }
        return TimelineLabelTemplates.applyTemplate(cfg.getUnmatchedAfter(),
                TimelineLabelTemplates.vars(clippedQuery, detail, null, detail));
    }

    /** 有 ExecutionPlan 时直接生成 after（写入时间线主行） */
    public String intentAfterForPlan(String userQuery, ExecutionPlan plan) {
        String q = StepSummarizer.clipQuery(userQuery);
        if (plan == null) {
            return intentAfterSummary(q, null);
        }
        if (plan.reason() != null && plan.reason().startsWith("user:forced")) {
            return forcedIntentAfterForPlan(q, plan);
        }
        return switch (plan.mode()) {
            case SIMPLE_LLM -> TimelineLabelTemplates.applyTemplate(
                    modeAfter(modeConfig(ExecutionMode.SIMPLE_LLM), "{query}属于简单对话，将直接生成回复"),
                    TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
            case REACT -> TimelineLabelTemplates.applyTemplate(
                    modeAfter(modeConfig(ExecutionMode.REACT), "{query}将由自主智能体分析并作答"),
                    TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
            case PLAN_WORKFLOW -> TimelineLabelTemplates.applyTemplate(
                    modeAfter(modeConfig(ExecutionMode.PLAN_WORKFLOW), "{query}将动态规划多步执行"),
                    TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
            case PEER_COLLAB -> TimelineLabelTemplates.applyTemplate(
                    modeAfter(modeConfig(ExecutionMode.PEER_COLLAB), "{query}将由多专家协作交叉验证"),
                    TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
            case WORKFLOW -> {
                WorkflowProperties.CatalogEntry entry = findCatalogById(plan.workflowId());
                String displayName = entry != null ? displayNameOf(entry)
                        : workflowNodeLabelService.workflowDisplayName(plan.workflowId());
                String template = entry != null && StringUtils.hasText(entry.getIntentAfter())
                        ? entry.getIntentAfter()
                        : modeAfter(modeConfig(ExecutionMode.WORKFLOW), "{query}将按「{displayName}」流程处理");
                yield TimelineLabelTemplates.applyTemplate(template,
                        TimelineLabelTemplates.vars(q, displayName, plan.workflowId(), displayName));
            }
        };
    }

    private AgentPromptProperties.ModeIntent modeConfig(ExecutionMode mode) {
        AgentPromptProperties.IntentTimeline cfg = agentPromptProperties.intentTimelineOrDefault();
        Map<String, AgentPromptProperties.ModeIntent> modes = cfg.getModes();
        if (modes == null) {
            return new AgentPromptProperties.ModeIntent();
        }
        AgentPromptProperties.ModeIntent found = modes.get(TimelineLabelTemplates.modeConfigKey(mode));
        return found != null ? found : new AgentPromptProperties.ModeIntent();
    }

    private WorkflowProperties.CatalogEntry findCatalogByDetail(String detail) {
        if (workflowProperties.getCatalog() == null) {
            return null;
        }
        for (WorkflowProperties.CatalogEntry entry : workflowProperties.getCatalog()) {
            if (detail.equals(entry.getId()) || detail.equals(displayNameOf(entry))) {
                return entry;
            }
        }
        return null;
    }

    private WorkflowProperties.CatalogEntry findCatalogById(String workflowId) {
        if (!StringUtils.hasText(workflowId) || workflowProperties.getCatalog() == null) {
            return null;
        }
        for (WorkflowProperties.CatalogEntry entry : workflowProperties.getCatalog()) {
            if (workflowId.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }

    private AgentPromptProperties.ModeIntent findModeByDetail(String detail) {
        AgentPromptProperties.IntentTimeline cfg = agentPromptProperties.intentTimelineOrDefault();
        if (cfg.getModes() == null) {
            return null;
        }
        for (AgentPromptProperties.ModeIntent mode : cfg.getModes().values()) {
            if (detail.equals(mode.getDetail())) {
                return mode;
            }
        }
        return null;
    }

    private static String displayNameOf(WorkflowProperties.CatalogEntry entry) {
        if (StringUtils.hasText(entry.getDisplayName())) {
            return entry.getDisplayName();
        }
        if (StringUtils.hasText(entry.getDesc())) {
            return entry.getDesc();
        }
        return entry.getId();
    }

    private String forcedIntentAfterForPlan(String q, ExecutionPlan plan) {
        AgentPromptProperties.ModeIntent mode = modeConfig(plan.mode());
        String template = modeForcedAfter(mode, plan.mode());
        if (plan.mode() == ExecutionMode.WORKFLOW) {
            WorkflowProperties.CatalogEntry entry = findCatalogById(plan.workflowId());
            String displayName = entry != null ? displayNameOf(entry)
                    : workflowNodeLabelService.workflowDisplayName(plan.workflowId());
            return TimelineLabelTemplates.applyTemplate(template,
                    TimelineLabelTemplates.vars(q, displayName, plan.workflowId(), displayName));
        }
        return TimelineLabelTemplates.applyTemplate(template,
                TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
    }

    private static String modeForcedAfter(AgentPromptProperties.ModeIntent mode, ExecutionMode executionMode) {
        if (StringUtils.hasText(mode.getForcedAfter())) {
            return mode.getForcedAfter();
        }
        return switch (executionMode) {
            case SIMPLE_LLM -> "{query}将按您指定的「简单对话」模式直接回复";
            case REACT -> "{query}将按您指定的「自主推理」模式处理";
            case WORKFLOW -> "{query}将按您指定的「工作流」模式处理";
            case PLAN_WORKFLOW -> "{query}将按您指定的「动态规划」模式处理";
            case PEER_COLLAB -> "{query}将按您指定的「多专家协作」模式处理";
        };
    }

    private static String modeDetail(AgentPromptProperties.ModeIntent mode, String fallback) {
        return StringUtils.hasText(mode.getDetail()) ? mode.getDetail() : fallback;
    }

    private static String modeAfter(AgentPromptProperties.ModeIntent mode, String fallback) {
        return StringUtils.hasText(mode.getAfter()) ? mode.getAfter() : fallback;
    }
}
