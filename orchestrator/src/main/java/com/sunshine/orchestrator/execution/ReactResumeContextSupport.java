package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.DecisionAnswer;
import com.sunshine.orchestrator.agent.DecisionOption;
import com.sunshine.orchestrator.agent.DecisionQuestion;
import com.sunshine.orchestrator.agent.DecisionResult;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps;
import com.sunshine.orchestrator.agent.RequestDecisionTool;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.TimelineStepId;
import com.sunshine.orchestrator.processing.ToolStepIds;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 暂停/HITL/继续生成：从 persisted steps 提取已完成推理、工具结果、已加载技能与任务板进度，
 * 注入 Agent prompt，使续跑接着进度而非从头规划。
 */
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
            if (isSkillPhase(phase, step.id())) {
                appendSkillBlock(blocks, step);
                continue;
            }
            if (isThinkPhase(phase)) {
                appendThinkBlock(blocks, step);
                continue;
            }
            if (isTasksPhase(phase, step.id())) {
                appendTasksBlock(blocks, step);
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
    public static String buildResolvedDecisionBlock(DecisionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户决策】");
        String title = result != null && result.title() != null ? result.title() : "";
        if (StringUtils.hasText(title)) {
            sb.append('\n').append(title.strip());
        }
        List<DecisionAnswer> answers =
                result != null && result.answers() != null ? result.answers() : List.of();
        sb.append('\n').append(RequestDecisionTool.formatSuccessResult(title, answers));
        return sb.toString();
    }

    private static boolean isDecisionPhase(String phase, String stepId) {
        return "decision".equals(phase) || (stepId != null && stepId.startsWith("decision-"));
    }

    private static boolean isSkippedPhase(String phase) {
        return TimelineStepId.INTENT.matches(phase)
                || TimelineStepId.PLAN.matches(phase)
                || TimelineStepId.GENERATE.matches(phase);
    }

    private static boolean isSkillPhase(String phase, String stepId) {
        return TimelineStepId.SKILL.matches(phase)
                || TimelineStepId.SKILL.matches(stepId)
                || (phase != null && phase.startsWith("skill"));
    }

    private static boolean isTasksPhase(String phase, String stepId) {
        return TimelineStepId.TASKS.matches(phase) || TimelineStepId.TASKS.matches(stepId);
    }

    private static boolean isThinkPhase(String phase) {
        return TimelineStepId.THINK.matches(phase) || TimelineStepId.AGENT.matches(phase) || phase.startsWith("think");
    }

    private static void appendSkillBlock(List<String> blocks, ProcessingStep step) {
        String skillId = step.metadata() != null ? step.metadata().skillId() : null;
        if (!StringUtils.hasText(skillId)) {
            skillId = firstNonBlank(summaryAfter(step.summary()), step.label());
        }
        if (!StringUtils.hasText(skillId)) {
            return;
        }
        blocks.add("【已加载技能】\n" + skillId.strip()
                + "\n技能物料已就绪；勿重新加载或从头执行该技能流程，接着已有进度继续。");
    }

    private static void appendTasksBlock(List<String> blocks, ProcessingStep step) {
        StepMetadata meta = step.metadata();
        if (meta == null || meta.tasks() == null || meta.tasks().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("【任务板】");
        String progress = firstNonBlank(meta.taskProgress(), summaryActive(step.summary()));
        if (StringUtils.hasText(progress)) {
            sb.append('\n').append("进度：").append(progress.strip());
        }
        for (var item : meta.tasks()) {
            if (item == null || !StringUtils.hasText(item.content())) {
                continue;
            }
            sb.append('\n').append("- [")
                    .append(StringUtils.hasText(item.status()) ? item.status().strip() : "pending")
                    .append("] ")
                    .append(item.content().strip());
        }
        sb.append("\n接着未完成项继续；勿重建整个任务板，勿把已完成项改回待办。");
        blocks.add(sb.toString());
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
        if (decision == null || decision.questions() == null || decision.questions().isEmpty()) {
            return;
        }
        if (StringUtils.hasText(decision.outcome())
                || (decision.answers() != null && !decision.answers().isEmpty())) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【待决策】");
        if (StringUtils.hasText(decision.title())) {
            sb.append('\n').append(decision.title().strip());
        }
        for (DecisionQuestion question : decision.questions()) {
            if (question == null || !StringUtils.hasText(question.prompt())) {
                continue;
            }
            sb.append('\n').append(question.prompt().strip());
            List<DecisionOption> options = question.options();
            if (options == null || options.isEmpty()) {
                continue;
            }
            sb.append("\n选项：");
            for (DecisionOption opt : options) {
                if (opt == null || !StringUtils.hasText(opt.id())) {
                    continue;
                }
                sb.append("\n- ").append(opt.id().strip()).append(": ");
                sb.append(StringUtils.hasText(opt.label()) ? opt.label().strip() : "");
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

    private static String summaryActive(StepSummary summary) {
        return summary != null ? summary.active() : null;
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
