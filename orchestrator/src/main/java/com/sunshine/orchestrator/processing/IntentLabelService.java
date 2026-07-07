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



import java.util.HashMap;

import java.util.Map;



/**

 * 意图步骤 detail / after 文案 — 从 Nacos agent.timeline + workflow catalog 解析，禁止硬编码流程名。

 * before / active 见 {@link #stepBefore} / {@link #stepActive}。

 * detail 仅用于路由解析，不下发时间线展开区；完成态主行用 after。

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
        TimelineLabels.bind(this);
        TimelineStepLabels.bind(this);
        ThinkStepLabels.bind(this);
    }

    /** think / think-N 标题，按执行模式与轮次解析 */
    public String thinkStepLabel(String stepId, ExecutionMode mode) {
        ExecutionMode resolved = mode != null ? mode : ExecutionMode.REACT;
        boolean first = ThinkStepIds.iterationOf(stepId) <= 1;
        AgentPromptProperties.StepTimeline think = stepTemplate(TimelineStepId.THINK.id());
        if (think == null) {
            return null;
        }
        String modeKey = modeConfigKey(resolved);
        AgentPromptProperties.StepModeTimeline modeTimeline = think.getModes() != null
                ? think.getModes().get(modeKey) : null;
        if (modeTimeline != null) {
            String modeLabel = first ? modeTimeline.getLabel() : modeTimeline.getLabelFollowUp();
            if (StringUtils.hasText(modeLabel)) {
                return modeLabel.strip();
            }
        }
        String label = first ? think.getLabel() : think.getLabelFollowUp();
        return StringUtils.hasText(label) ? label.strip() : null;
    }

    /** think / think-N 步骤 before，占位符 {query} {toolDisplayName} */
    public String thinkStepBefore(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return applyThinkTemplate(stepId, mode, clippedQuery, toolDisplayName, ThinkPhase.BEFORE);
    }

    /** think / think-N 步骤 active */
    public String thinkStepActive(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return applyThinkTemplate(stepId, mode, clippedQuery, toolDisplayName, ThinkPhase.ACTIVE);
    }

    /** think / think-N 步骤 after（detail 非空时由调用方优先使用） */
    public String thinkStepAfter(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return applyThinkTemplate(stepId, mode, clippedQuery, toolDisplayName, ThinkPhase.AFTER);
    }

    private String applyThinkTemplate(String stepId, ExecutionMode mode, String clippedQuery,
            String toolDisplayName, ThinkPhase phase) {
        ExecutionMode resolved = mode != null ? mode : ExecutionMode.REACT;
        AgentPromptProperties.StepTimeline root = stepTemplate(TimelineStepId.THINK.id());
        if (root == null) {
            return null;
        }
        boolean first = ThinkStepIds.iterationOf(stepId) <= 1;
        boolean hasQuery = StringUtils.hasText(clippedQuery);
        boolean hasTool = StringUtils.hasText(toolDisplayName);
        AgentPromptProperties.StepModeTimeline modeTimeline = resolveModeTimeline(root, resolved);
        String template = pickThinkTemplate(root, modeTimeline, first, hasQuery, hasTool, phase);
        if (!StringUtils.hasText(template)) {
            return null;
        }
        String query = hasQuery ? clippedQuery.strip() : "";
        return applyTemplate(template.strip(), thinkVars(query, toolDisplayName));
    }

    private static AgentPromptProperties.StepModeTimeline resolveModeTimeline(
            AgentPromptProperties.StepTimeline root, ExecutionMode mode) {
        if (root.getModes() == null) {
            return null;
        }
        return root.getModes().get(modeConfigKey(mode));
    }

    private static String pickThinkTemplate(AgentPromptProperties.StepTimeline root,
            AgentPromptProperties.StepModeTimeline modeTimeline,
            boolean first, boolean hasQuery, boolean hasTool, ThinkPhase phase) {
        if (modeTimeline != null) {
            if (first) {
                String modeTemplate = phase.modeField(modeTimeline, true);
                if (StringUtils.hasText(modeTemplate)) {
                    return modeTemplate;
                }
            } else {
                String modeFollowUp = phase.modeFollowUpField(modeTimeline);
                if (StringUtils.hasText(modeFollowUp)) {
                    return modeFollowUp;
                }
            }
        }
        if (first) {
            if (hasQuery) {
                return coalesce(phase.rootField(root, true));
            }
            return coalesce(phase.rootFallbackField(root));
        }
        if (hasTool) {
            return coalesce(phase.rootFollowUpField(root));
        }
        if (hasQuery) {
            return coalesce(phase.rootFollowUpNoToolField(root));
        }
        return coalesce(phase.rootFollowUpFallbackField(root));
    }

    private static Map<String, String> thinkVars(String clippedQuery, String toolDisplayName) {
        Map<String, String> map = new HashMap<>();
        map.put("query", clippedQuery != null ? clippedQuery : "");
        map.put("toolDisplayName", bracketTool(toolDisplayName));
        return map;
    }

    private static String bracketTool(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return "";
        }
        return "「" + displayName.strip() + "」";
    }

    private static String coalesce(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private enum ThinkPhase {
        BEFORE {
            @Override
            String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first) {
                return mode.getBefore();
            }

            @Override
            String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode) {
                return mode.getBeforeFollowUp();
            }

            @Override
            String rootField(AgentPromptProperties.StepTimeline root, boolean first) {
                return root.getBefore();
            }

            @Override
            String rootFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFallback();
            }

            @Override
            String rootFollowUpField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFollowUp();
            }

            @Override
            String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFollowUpNoTool();
            }

            @Override
            String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getBeforeFollowUpFallback();
            }
        },
        ACTIVE {
            @Override
            String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first) {
                return mode.getActive();
            }

            @Override
            String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode) {
                return mode.getActiveFollowUp();
            }

            @Override
            String rootField(AgentPromptProperties.StepTimeline root, boolean first) {
                return root.getActive();
            }

            @Override
            String rootFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFallback();
            }

            @Override
            String rootFollowUpField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFollowUp();
            }

            @Override
            String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFollowUpNoTool();
            }

            @Override
            String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getActiveFollowUpFallback();
            }
        },
        AFTER {
            @Override
            String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first) {
                return mode.getAfter();
            }

            @Override
            String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode) {
                return mode.getAfterFollowUp();
            }

            @Override
            String rootField(AgentPromptProperties.StepTimeline root, boolean first) {
                return root.getAfter();
            }

            @Override
            String rootFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFallback();
            }

            @Override
            String rootFollowUpField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFollowUp();
            }

            @Override
            String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFollowUpNoTool();
            }

            @Override
            String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root) {
                return root.getAfterFollowUpFallback();
            }
        };

        abstract String modeField(AgentPromptProperties.StepModeTimeline mode, boolean first);

        abstract String modeFollowUpField(AgentPromptProperties.StepModeTimeline mode);

        abstract String rootField(AgentPromptProperties.StepTimeline root, boolean first);

        abstract String rootFallbackField(AgentPromptProperties.StepTimeline root);

        abstract String rootFollowUpField(AgentPromptProperties.StepTimeline root);

        abstract String rootFollowUpNoToolField(AgentPromptProperties.StepTimeline root);

        abstract String rootFollowUpFallbackField(AgentPromptProperties.StepTimeline root);
    }

    private static String modeConfigKey(ExecutionMode mode) {
        return switch (mode) {
            case SIMPLE_LLM -> "simple-llm";
            case WORKFLOW -> "workflow";
            case PLAN_WORKFLOW -> "plan-workflow";
            default -> "react";
        };
    }

    /** 标准步骤标题（intent / plan / rag 等），SSOT 见 Nacos agent.timeline */
    public String stepLabel(String stepId) {
        if (TimelineStepId.INTENT.matches(stepId)) {
            return textOrDefault(agentPromptProperties.intentTimelineOrDefault().getLabel(), "识别意图");
        }
        AgentPromptProperties.StepTimeline step = stepTemplate(stepId);
        if (step != null && StringUtils.hasText(step.getLabel())) {
            return step.getLabel().strip();
        }
        return null;
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    public String stepBefore(String stepId, String clippedQuery) {

        if (TimelineStepId.INTENT.matches(stepId)) {

            return applyTemplate(

                    agentPromptProperties.intentTimelineOrDefault().getBefore(),

                    vars(clippedQuery, null, null, null));

        }

        AgentPromptProperties.StepTimeline step = stepTemplate(stepId);

        if (step != null && StringUtils.hasText(step.getBefore())) {

            return applyTemplate(step.getBefore(), vars(clippedQuery, null, null, null));

        }

        return StepLabels.beforeFor(stepId);

    }



    public String stepActive(String stepId, String clippedQuery) {

        if (TimelineStepId.INTENT.matches(stepId)) {

            return applyTemplate(

                    agentPromptProperties.intentTimelineOrDefault().getActive(),

                    vars(clippedQuery, null, null, null));

        }

        AgentPromptProperties.StepTimeline step = stepTemplate(stepId);

        if (step != null && StringUtils.hasText(step.getActive())) {

            return applyTemplate(step.getActive(), vars(clippedQuery, null, null, null));

        }

        return StepLabels.activeFor(stepId);

    }

    /** plan / generate / skill 等步骤 after 模板，占位符 {query} */
    public String stepAfter(String stepId, String clippedQuery, String detail) {
        if (TimelineStepId.PLAN.matches(stepId)) {
            if (StringUtils.hasText(detail)) {
                return detail.strip();
            }
            AgentPromptProperties.StepTimeline step = stepTemplate(TimelineStepId.PLAN.id());
            return textOrDefault(step != null ? step.getAfter() : null, "执行计划已生成");
        }
        if (TimelineStepId.GENERATE.matches(stepId)) {
            AgentPromptProperties.StepTimeline step = stepTemplate(TimelineStepId.GENERATE.id());
            String template = textOrDefault(step != null ? step.getAfter() : null, "已完成对{query}的回复");
            return applyTemplate(template, vars(clippedQuery, null, null, null));
        }
        if (TimelineStepId.SKILL.matches(stepId)) {
            if (StringUtils.hasText(detail)) {
                return detail.strip();
            }
            AgentPromptProperties.StepTimeline step = stepTemplate(TimelineStepId.SKILL.id());
            return textOrDefault(step != null ? step.getAfterFallback() : null, "Skill 已加载");
        }
        return null;
    }



    private AgentPromptProperties.StepTimeline stepTemplate(String stepId) {

        var steps = agentPromptProperties.timelineOrDefault().getSteps();

        if (steps == null || !StringUtils.hasText(stepId)) {

            return null;

        }

        return steps.get(stepId);

    }



    public String intentDetail(ExecutionPlan plan) {

        if (plan == null) {

            return modeDetail(modeConfig(ExecutionMode.SIMPLE_LLM), "简单对话");

        }

        return switch (plan.mode()) {

            case SIMPLE_LLM -> modeDetail(modeConfig(ExecutionMode.SIMPLE_LLM), "简单对话");

            case REACT -> modeDetail(modeConfig(ExecutionMode.REACT), "自主智能体");

            case PLAN_WORKFLOW -> modeDetail(modeConfig(ExecutionMode.PLAN_WORKFLOW), "动态规划");

            case WORKFLOW -> workflowNodeLabelService.workflowDisplayName(plan.workflowId());

        };

    }



    public String intentAfterSummary(String clippedQuery, String detail) {

        AgentPromptProperties.IntentTimeline cfg = agentPromptProperties.intentTimelineOrDefault();

        if (!StringUtils.hasText(detail)) {

            return applyTemplate(cfg.getDefaultAfter(), vars(clippedQuery, detail, null, null));

        }

        WorkflowProperties.CatalogEntry catalogEntry = findCatalogByDetail(detail);

        if (catalogEntry != null) {

            String template = StringUtils.hasText(catalogEntry.getIntentAfter())

                    ? catalogEntry.getIntentAfter()

                    : modeAfter(modeConfig(ExecutionMode.WORKFLOW), "{query}将按「{displayName}」流程处理");

            return applyTemplate(template, vars(

                    clippedQuery,

                    detail,

                    catalogEntry.getId(),

                    displayNameOf(catalogEntry)));

        }

        AgentPromptProperties.ModeIntent mode = findModeByDetail(detail);

        if (mode != null && StringUtils.hasText(mode.getAfter())) {

            return applyTemplate(mode.getAfter(), vars(clippedQuery, detail, null, detail));

        }

        return applyTemplate(cfg.getUnmatchedAfter(), vars(clippedQuery, detail, null, detail));

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

            case SIMPLE_LLM -> applyTemplate(

                    modeAfter(modeConfig(ExecutionMode.SIMPLE_LLM), "{query}属于简单对话，将直接生成回复"),

                    vars(q, intentDetail(plan), null, null));

            case REACT -> applyTemplate(

                    modeAfter(modeConfig(ExecutionMode.REACT), "{query}将由自主智能体分析并作答"),

                    vars(q, intentDetail(plan), null, null));

            case PLAN_WORKFLOW -> applyTemplate(

                    modeAfter(modeConfig(ExecutionMode.PLAN_WORKFLOW), "{query}将动态规划多步执行"),

                    vars(q, intentDetail(plan), null, null));

            case WORKFLOW -> {

                WorkflowProperties.CatalogEntry entry = findCatalogById(plan.workflowId());

                String displayName = entry != null ? displayNameOf(entry)

                        : workflowNodeLabelService.workflowDisplayName(plan.workflowId());

                String template = entry != null && StringUtils.hasText(entry.getIntentAfter())

                        ? entry.getIntentAfter()

                        : modeAfter(modeConfig(ExecutionMode.WORKFLOW), "{query}将按「{displayName}」流程处理");

                yield applyTemplate(template, vars(q, displayName, plan.workflowId(), displayName));

            }

        };

    }



    private AgentPromptProperties.ModeIntent modeConfig(ExecutionMode mode) {

        AgentPromptProperties.IntentTimeline cfg = agentPromptProperties.intentTimelineOrDefault();

        Map<String, AgentPromptProperties.ModeIntent> modes = cfg.getModes();

        if (modes == null) {

            return new AgentPromptProperties.ModeIntent();

        }

        AgentPromptProperties.ModeIntent found = modes.get(modeKey(mode));

        return found != null ? found : new AgentPromptProperties.ModeIntent();

    }



    private static String modeKey(ExecutionMode mode) {

        return switch (mode) {

            case SIMPLE_LLM -> "simple-llm";

            case WORKFLOW -> "workflow";

            case REACT -> "react";

            case PLAN_WORKFLOW -> "plan-workflow";

        };

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



    private static Map<String, String> vars(String query, String detail, String workflowId, String displayName) {

        Map<String, String> map = new HashMap<>();

        map.put("query", query != null ? query : "");

        map.put("detail", detail != null ? detail : "");

        map.put("workflowId", workflowId != null ? workflowId : "");

        map.put("displayName", displayName != null ? displayName : "");

        return map;

    }



    private String forcedIntentAfterForPlan(String q, ExecutionPlan plan) {

        AgentPromptProperties.ModeIntent mode = modeConfig(plan.mode());

        String template = modeForcedAfter(mode, plan.mode());

        if (plan.mode() == ExecutionMode.WORKFLOW) {

            WorkflowProperties.CatalogEntry entry = findCatalogById(plan.workflowId());

            String displayName = entry != null ? displayNameOf(entry)

                    : workflowNodeLabelService.workflowDisplayName(plan.workflowId());

            return applyTemplate(template, vars(q, displayName, plan.workflowId(), displayName));

        }

        return applyTemplate(template, vars(q, intentDetail(plan), null, null));

    }



    private String modeForcedAfter(AgentPromptProperties.ModeIntent mode, ExecutionMode executionMode) {

        if (StringUtils.hasText(mode.getForcedAfter())) {

            return mode.getForcedAfter();

        }

        return switch (executionMode) {

            case SIMPLE_LLM -> "{query}将按您指定的「简单对话」模式直接回复";

            case REACT -> "{query}将按您指定的「自主推理」模式处理";

            case WORKFLOW -> "{query}将按您指定的「工作流」模式处理";

            case PLAN_WORKFLOW -> "{query}将按您指定的「动态规划」模式处理";

        };

    }



    private static String modeDetail(AgentPromptProperties.ModeIntent mode, String fallback) {

        return StringUtils.hasText(mode.getDetail()) ? mode.getDetail() : fallback;

    }



    private static String modeAfter(AgentPromptProperties.ModeIntent mode, String fallback) {

        return StringUtils.hasText(mode.getAfter()) ? mode.getAfter() : fallback;

    }



    private static String applyTemplate(String template, Map<String, String> vars) {

        if (!StringUtils.hasText(template)) {

            return "";

        }

        String result = template;

        for (Map.Entry<String, String> e : vars.entrySet()) {

            result = result.replace("{" + e.getKey() + "}", e.getValue());

        }

        return result;

    }

}

