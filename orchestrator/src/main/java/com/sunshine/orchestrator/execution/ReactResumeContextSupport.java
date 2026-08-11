package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.DecisionOption;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.TimelineStepId;
import com.sunshine.orchestrator.processing.ToolStepIds;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** ReAct 暂停/HITL 续跑：从 persisted steps 提取已完成推理与工具结果，注入 Agent prompt */
public final class ReactResumeContextSupport {

    private ReactResumeContextSupport() {
    }

    public static List<String> buildInjectedBlocks(List<ProcessingStep> steps) {
        return buildInjectedBlocks(steps, true);
    }

    /**
     * @param includeAwaitingDecision false 用于 reactRestart：【待决策】改由
     * {@link com.sunshine.orchestrator.agent.DecisionResumeSupport} 完成后注入【用户决策】，避免 await 前陈旧文案
     */
    public static List<String> buildInjectedBlocks(List<ProcessingStep> steps, boolean includeAwaitingDecision) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        for (ProcessingStep step : steps) {
            if (step == null || step.id() == null || step.id().startsWith("node-")) {
                continue;
            }
            String phase = step.phase();
            if (phase == null || isSkippedPhase(phase)) {
                continue;
            }
            if (isThinkPhase(phase)) {
                appendThinkBlock(blocks, step);
                continue;
            }
            if (isDecisionPhase(phase, step.id())) {
                if (includeAwaitingDecision) {
                    appendAwaitingDecisionBlock(blocks, step);
                }
                continue;
            }
            if (ToolStepIds.isToolStep(step.id())) {
                appendToolBlock(blocks, step);
            }
        }
        return List.copyOf(blocks);
    }

    /**
     * 续跑用户已选：短格式原文注入（不截断），即使 checkpoint 不再重放 request_decision 也能进入模型上下文。
     */
    public static String buildResolvedDecisionBlock(
            String question, String choice, String label, String customInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户决策】");
        if (StringUtils.hasText(question)) {
            sb.append('\n').append(question.strip());
        }
        sb.append('\n').append(com.sunshine.orchestrator.agent.RequestDecisionTool.formatSuccessResult(
                choice, label, customInput));
        return sb.toString();
    }

    private static boolean isDecisionPhase(String phase, String stepId) {
        return "decision".equals(phase) || (stepId != null && stepId.startsWith("decision-"));
    }

    private static boolean isSkippedPhase(String phase) {
        return TimelineStepId.INTENT.matches(phase)
                || TimelineStepId.PLAN.matches(phase)
                || TimelineStepId.GENERATE.matches(phase)
                || TimelineStepId.SKILL.matches(phase)
                || phase.startsWith("skill");
    }

    private static boolean isThinkPhase(String phase) {
        return TimelineStepId.THINK.matches(phase) || TimelineStepId.AGENT.matches(phase) || phase.startsWith("think");
    }

    private static void appendThinkBlock(List<String> blocks, ProcessingStep step) {
        String text = firstNonBlank(
                step.reasoning(),
                summaryAfter(step.summary()),
                step.detail(),
                step.result(),
                step.output());
        if (!StringUtils.hasText(text)) {
            return;
        }
        String label = StringUtils.hasText(step.label()) ? step.label().strip() : step.id();
        blocks.add("【思考 " + label + "】\n" + text.strip());
    }

    private static void appendToolBlock(List<String> blocks, ProcessingStep step) {
        if (ProcessingStepLifecycleOps.isAwaitingInteractionStep(step)) {
            appendAwaitingHitlBlock(blocks, step);
            return;
        }
        if (!isCompletedLifecycle(step)) {
            return;
        }
        String text = firstNonBlank(
                step.result(),
                step.output(),
                summaryAfter(step.summary()),
                step.detail());
        if (!StringUtils.hasText(text)) {
            return;
        }
        String toolName = ToolStepIds.catalogToolName(step.id());
        blocks.add("【工具 " + toolName + "】\n" + text.strip());
    }

    private static void appendAwaitingHitlBlock(List<String> blocks, ProcessingStep step) {
        StepMetadata meta = step.metadata();
        if (meta == null || meta.hitl() == null) {
            return;
        }
        HitlStepMeta hitl = meta.hitl();
        if (!StringUtils.hasText(hitl.paramsSummary())) {
            return;
        }
        String displayName = StringUtils.hasText(hitl.toolDisplayName())
                ? hitl.toolDisplayName().strip()
                : ToolStepIds.catalogToolName(step.id());
        blocks.add("【待确认写操作 " + displayName + "】\n参数：" + hitl.paramsSummary().strip());
    }

    private static void appendAwaitingDecisionBlock(List<String> blocks, ProcessingStep step) {
        String lifecycle = step.lifecycle();
        if (!"awaiting".equals(lifecycle) && !"paused".equals(lifecycle)) {
            return;
        }
        DecisionStepMeta decision = step.metadata() != null ? step.metadata().decision() : null;
        if (decision == null || !StringUtils.hasText(decision.question())) {
            return;
        }
        if (StringUtils.hasText(decision.choice())) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【待决策】\n").append(decision.question().strip());
        List<DecisionOption> options = decision.options();
        if (options != null && !options.isEmpty()) {
            sb.append("\n选项：");
            for (DecisionOption opt : options) {
                if (opt == null || !StringUtils.hasText(opt.value())) {
                    continue;
                }
                sb.append("\n- ").append(opt.value().strip()).append(": ");
                sb.append(StringUtils.hasText(opt.label()) ? opt.label().strip() : "");
                if (StringUtils.hasText(opt.description())) {
                    sb.append(" — ").append(opt.description());
                }
            }
        }
        blocks.add(sb.toString());
    }

    private static boolean isCompletedLifecycle(ProcessingStep step) {
        String lifecycle = step.lifecycle();
        if ("done".equals(lifecycle)) {
            return true;
        }
        return "paused".equals(lifecycle)
                && StringUtils.hasText(firstNonBlank(step.result(), step.output()));
    }

    private static String summaryAfter(StepSummary summary) {
        return summary != null ? summary.after() : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
