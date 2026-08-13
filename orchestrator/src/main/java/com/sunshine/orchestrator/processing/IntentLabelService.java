package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.WorkflowCatalogRegistry;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
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

    private final TimelinePromptCatalog timelinePromptCatalog;
    private final WorkflowCatalog workflowCatalog;
    private final WorkflowCatalogRegistry workflowCatalogRegistry;
    private final WorkflowNodeLabelService workflowNodeLabelService;

    @PostConstruct
    void init() {
        IntentLabels.bind(this);
    }

    public String intentDetail(ExecutionPlan plan) {
        if (plan == null) {
            return modeDetail(modeConfig(ExecutionMode.FAST), "自主智能体");
        }
        return switch (plan.mode()) {
            case FAST -> modeDetail(modeConfig(ExecutionMode.FAST), "自主智能体");
            case PRO -> modeDetail(modeConfig(ExecutionMode.PRO), "动态规划");
            case WORKFLOW -> workflowNodeLabelService.workflowDisplayName(plan.workflowId());
        };
    }

    public String intentAfterSummary(String clippedQuery, String detail) {
        AgentPromptProperties.IntentTimeline cfg = timelinePromptCatalog.intent();
        if (!StringUtils.hasText(detail)) {
            return TimelineLabelTemplates.applyTemplate(cfg.getDefaultAfter(),
                    TimelineLabelTemplates.vars(clippedQuery, detail, null, null));
        }
        WorkflowManagerClient.WorkflowCatalogEntryDto catalogEntry = findCatalogByDetail(detail);
        if (catalogEntry != null) {
            String template = modeAfter(modeConfig(ExecutionMode.WORKFLOW), "将按「{displayName}」流程处理");
            return TimelineLabelTemplates.applyTemplate(template, TimelineLabelTemplates.vars(
                    clippedQuery, detail, catalogEntry.id(), displayNameOf(catalogEntry)));
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
            case FAST -> TimelineLabelTemplates.applyTemplate(
                    modeAfter(modeConfig(ExecutionMode.FAST), "将由自主智能体分析并作答"),
                    TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
            case PRO -> TimelineLabelTemplates.applyTemplate(
                    modeAfter(modeConfig(ExecutionMode.PRO), "将动态规划多步执行"),
                    TimelineLabelTemplates.vars(q, intentDetail(plan), null, null));
            case WORKFLOW -> {
                WorkflowManagerClient.WorkflowCatalogEntryDto entry = workflowCatalog.findEntry(plan.workflowId());
                String displayName = entry != null ? displayNameOf(entry)
                        : workflowNodeLabelService.workflowDisplayName(plan.workflowId());
                String template = workflowIntentAfterTemplate(entry);
                yield TimelineLabelTemplates.applyTemplate(template,
                        TimelineLabelTemplates.vars(q, displayName, plan.workflowId(), displayName));
            }
        };
    }

    private AgentPromptProperties.ModeIntent modeConfig(ExecutionMode mode) {
        AgentPromptProperties.IntentTimeline cfg = timelinePromptCatalog.intent();
        Map<String, AgentPromptProperties.ModeIntent> modes = cfg.getModes();
        if (modes == null) {
            return new AgentPromptProperties.ModeIntent();
        }
        AgentPromptProperties.ModeIntent found = modes.get(TimelineLabelTemplates.modeConfigKey(mode));
        return found != null ? found : new AgentPromptProperties.ModeIntent();
    }

    private WorkflowManagerClient.WorkflowCatalogEntryDto findCatalogByDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return null;
        }
        for (WorkflowManagerClient.WorkflowCatalogEntryDto entry : workflowCatalogRegistry.entries()) {
            if (detail.equals(entry.id()) || detail.equals(displayNameOf(entry))) {
                return entry;
            }
        }
        return null;
    }

    private WorkflowManagerClient.WorkflowCatalogEntryDto findCatalogById(String workflowId) {
        return workflowCatalog.findEntry(workflowId);
    }

    private AgentPromptProperties.ModeIntent findModeByDetail(String detail) {
        AgentPromptProperties.IntentTimeline cfg = timelinePromptCatalog.intent();
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

    private static String displayNameOf(WorkflowManagerClient.WorkflowCatalogEntryDto entry) {
        if (StringUtils.hasText(entry.displayName())) {
            return entry.displayName();
        }
        if (StringUtils.hasText(entry.description())) {
            return entry.description();
        }
        return entry.id();
    }

    private String forcedIntentAfterForPlan(String q, ExecutionPlan plan) {
        AgentPromptProperties.ModeIntent mode = modeConfig(plan.mode());
        String template = modeForcedAfter(mode, plan.mode());
        if (plan.mode() == ExecutionMode.WORKFLOW) {
            WorkflowManagerClient.WorkflowCatalogEntryDto entry = findCatalogById(plan.workflowId());
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
            case FAST -> "将按您指定的「自主推理」模式处理";
            case WORKFLOW -> "将按您指定的「工作流」模式处理";
            case PRO -> "将按您指定的「动态规划」模式处理";
        };
    }

    private static String modeDetail(AgentPromptProperties.ModeIntent mode, String fallback) {
        return StringUtils.hasText(mode.getDetail()) ? mode.getDetail() : fallback;
    }

    private static String modeAfter(AgentPromptProperties.ModeIntent mode, String fallback) {
        return StringUtils.hasText(mode.getAfter()) ? mode.getAfter() : fallback;
    }

    private String workflowIntentAfterTemplate(WorkflowManagerClient.WorkflowCatalogEntryDto entry) {
        if (entry != null && StringUtils.hasText(entry.intentAfter())) {
            return entry.intentAfter().strip();
        }
        return modeAfter(modeConfig(ExecutionMode.WORKFLOW), "将按「{displayName}」流程处理");
    }
}
