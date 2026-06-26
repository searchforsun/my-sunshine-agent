package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 时间线步骤结构化元数据（如 RAG 命中数与来源文档、QueryRewrite 可观测） */
public record StepMetadata(
        Integer hitCount,
        List<String> sources,
        Boolean rewriteApplied,
        Long rewriteLatencyMs,
        String rewriteFrom,
        String rewriteTo,
        String rewriteScenario,
        String rewriteScenarioLabel,
        /** L0 Skill 绑定：intent 步可观测 */
        String skillId,
        String plannerMode,
        String routingReason,
        /** RAG 等：改写链路已在 detail 正文，前端勿再渲染 metadata 结构化改写区 */
        Boolean rewriteInDetail,
        /** 抽屉/展开区 detail 区块标题（如「检索过程」） */
        String expandSectionTitle,
        /** 写工具 HITL 确认态（awaiting 时前端展示内联按钮） */
        HitlStepMeta hitl,
        /** Workflow 节点失败：用户重试/终止 */
        NodeRecoveryMeta recovery,
        /** Workflow 节点执行 attempt 列表（重试过程实时下发） */
        List<NodeAttemptMeta> nodeAttempts,
        /** 动态 Plan 用户确认轮次 */
        PlanApprovalMeta planApproval
) {

    private static final String RAG_EXPAND_SECTION_TITLE = "检索过程";

    private static final Pattern HIT_COUNT = Pattern.compile("(?:共|命中)\\s*(\\d+)\\s*条");
    private static final Pattern NO_HIT_HEADER = Pattern.compile("^未找到相关知识库");
    private static final Pattern SOURCE_DOC_LINE = Pattern.compile("来源文档[：:]\\s*([^\\n【]+)");
    private static final Pattern SOURCE_SUMMARY_LINE = Pattern.compile("来源[：:]\\s*([^\\n【]+)");
    private static final Pattern FRAGMENT_DOC = Pattern.compile("【([^|】]+)\\s*\\|");
    private static final int MAX_DOC_NAME_LEN = 80;

    /** 从 RAG 工具原始或已摘要文本提取命中数与去重文档标题 */
    public static StepMetadata fromRagToolOutput(String text) {
        return fromRagToolOutput(text, text);
    }

    /** 原始正文提取文档名，摘要行补充命中数 */
    public static StepMetadata fromRagToolOutput(String rawText, String summarizedText) {
        if (isEmptyRagOutput(rawText) && isEmptyRagOutput(summarizedText)) {
            return emptyRag();
        }
        int hitCount = parseHitCount(rawText);
        if (hitCount == 0 && summarizedText != null && !summarizedText.isBlank()) {
            hitCount = parseHitCount(summarizedText);
        }
        List<String> sources = parseSources(rawText);
        if (sources.isEmpty() && summarizedText != null && !summarizedText.isBlank()) {
            sources = parseSources(summarizedText);
        }
        return new StepMetadata(hitCount, sources, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StepMetadata fromRouting(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        Map<String, String> params = plan.params() != null ? plan.params() : Map.of();
        String skill = params.get(SkillBindingOutcome.PARAM_SKILL);
        String mode = params.get(SkillBindingOutcome.PARAM_PLANNER_MODE);
        String reason = plan.reason();
        if (!StringUtils.hasText(skill) && !StringUtils.hasText(mode) && !StringUtils.hasText(reason)) {
            return null;
        }
        return new StepMetadata(
                null, null, null, null, null, null, null, null,
                textOrNull(skill), textOrNull(mode), textOrNull(reason), null, null, null, null, null, null);
    }

    /** skill 步：L0 绑定可观测 */
    public static StepMetadata fromSkillLoad(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return null;
        }
        return new StepMetadata(
                null, null, null, null, null, null, null, null,
                skillId.strip(), null, null, null, null, null, null, null, null);
    }

    public static StepMetadata mergeRouting(StepMetadata base, ExecutionPlan plan) {
        StepMetadata routing = fromRouting(plan);
        if (routing == null) {
            return base;
        }
        if (base == null) {
            return routing;
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                routing.skillId(),
                routing.plannerMode(),
                routing.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                base.recovery(),
                base.nodeAttempts(),
                base.planApproval());
    }

    /** 写工具步骤：挂载 HITL 待确认 metadata */
    public static StepMetadata withHitl(StepMetadata base, HitlStepMeta hitl) {
        if (hitl == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, hitl, null, null, null);
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                hitl,
                base.recovery(),
                base.nodeAttempts(),
                base.planApproval());
    }

    /** 节点已成功完成：清除 recovery 元数据（保留 skip 态由调用方决定） */
    public static StepMetadata withoutRecovery(StepMetadata base) {
        if (base == null || base.recovery() == null) {
            return base;
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                null,
                base.nodeAttempts(),
                base.planApproval());
    }

    /** Workflow 节点 attempt 列表更新（重试过程实时下发） */
    public static StepMetadata withNodeAttempts(StepMetadata base, List<NodeAttemptMeta> nodeAttempts) {
        if (nodeAttempts == null || nodeAttempts.isEmpty()) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, List.copyOf(nodeAttempts), null);
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                base.recovery(),
                List.copyOf(nodeAttempts),
                base.planApproval());
    }

    /** Workflow 节点失败：挂载重试/终止 metadata */
    public static StepMetadata withRecovery(StepMetadata base, NodeRecoveryMeta recovery) {
        if (recovery == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, recovery, null, null);
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                recovery,
                base.nodeAttempts(),
                base.planApproval());
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    public static StepMetadata fromRewrite(com.sunshine.orchestrator.rewrite.QueryRewriteOutcome outcome) {
        if (outcome == null || !outcome.applied()) {
            return null;
        }
        String scenarioLabel = RewriteTimelineLabels.labelFor(outcome.scenario());
        return new StepMetadata(
                null,
                null,
                true,
                outcome.latencyMs(),
                outcome.originalQuery(),
                outcome.rewrittenQuery(),
                outcome.scenario(),
                scenarioLabel.isBlank() ? null : scenarioLabel,
                null, null, null, null, null, null, null, null, null);
    }

    public static StepMetadata withPlanApproval(StepMetadata base, PlanApprovalMeta planApproval) {
        if (planApproval == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, planApproval);
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                base.recovery(),
                base.nodeAttempts(),
                planApproval);
    }

    public static StepMetadata mergeRewrite(StepMetadata base, com.sunshine.orchestrator.rewrite.QueryRewriteOutcome outcome) {
        StepMetadata rewriteMeta = fromRewrite(outcome);
        if (rewriteMeta == null) {
            return base;
        }
        if (base == null) {
            return rewriteMeta;
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                rewriteMeta.rewriteApplied(),
                rewriteMeta.rewriteLatencyMs(),
                rewriteMeta.rewriteFrom(),
                rewriteMeta.rewriteTo(),
                rewriteMeta.rewriteScenario(),
                rewriteMeta.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                base.recovery(),
                base.nodeAttempts(),
                base.planApproval());
    }

    /** 步骤 metadata 增量合并 — 避免后续 SSE step 冲掉 planApproval 等字段 */
    public static StepMetadata merge(StepMetadata base, StepMetadata overlay) {
        if (overlay == null) {
            return base;
        }
        if (base == null) {
            return overlay;
        }
        return new StepMetadata(
                overlay.hitCount() != null ? overlay.hitCount() : base.hitCount(),
                overlay.sources() != null && !overlay.sources().isEmpty() ? overlay.sources() : base.sources(),
                overlay.rewriteApplied() != null ? overlay.rewriteApplied() : base.rewriteApplied(),
                overlay.rewriteLatencyMs() != null ? overlay.rewriteLatencyMs() : base.rewriteLatencyMs(),
                overlay.rewriteFrom() != null ? overlay.rewriteFrom() : base.rewriteFrom(),
                overlay.rewriteTo() != null ? overlay.rewriteTo() : base.rewriteTo(),
                overlay.rewriteScenario() != null ? overlay.rewriteScenario() : base.rewriteScenario(),
                overlay.rewriteScenarioLabel() != null ? overlay.rewriteScenarioLabel() : base.rewriteScenarioLabel(),
                overlay.skillId() != null ? overlay.skillId() : base.skillId(),
                overlay.plannerMode() != null ? overlay.plannerMode() : base.plannerMode(),
                overlay.routingReason() != null ? overlay.routingReason() : base.routingReason(),
                overlay.rewriteInDetail() != null ? overlay.rewriteInDetail() : base.rewriteInDetail(),
                overlay.expandSectionTitle() != null ? overlay.expandSectionTitle() : base.expandSectionTitle(),
                overlay.hitl() != null ? overlay.hitl() : base.hitl(),
                overlay.recovery() != null ? overlay.recovery() : base.recovery(),
                overlay.nodeAttempts() != null && !overlay.nodeAttempts().isEmpty()
                        ? overlay.nodeAttempts() : base.nodeAttempts(),
                overlay.planApproval() != null ? overlay.planApproval() : base.planApproval());
    }

    /** Plan 确认步：规划改写仅进抽屉，勿在主时间线展开区重复展示 */
    public static StepMetadata withRewriteInDetail(StepMetadata base) {
        if (base == null) {
            return null;
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                true,
                base.expandSectionTitle(),
                base.hitl(),
                base.recovery(),
                base.nodeAttempts(),
                base.planApproval());
    }

    public static StepMetadata withRagExpandLayout(StepMetadata base) {
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, true, RAG_EXPAND_SECTION_TITLE, null, null, null, null);
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                true,
                RAG_EXPAND_SECTION_TITLE,
                base.hitl(),
                base.recovery(),
                base.nodeAttempts(),
                base.planApproval());
    }

    private static StepMetadata emptyRag() {
        return new StepMetadata(0, List.of(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public String sourcesLabel() {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return String.join("、", sources);
    }

    public boolean isEmpty() {
        return (hitCount == null || hitCount == 0)
                && (sources == null || sources.isEmpty())
                && (rewriteApplied == null || !rewriteApplied)
                && !StringUtils.hasText(skillId)
                && !StringUtils.hasText(plannerMode)
                && !StringUtils.hasText(routingReason)
                && hitl == null
                && recovery == null
                && (nodeAttempts == null || nodeAttempts.isEmpty())
                && planApproval == null;
    }

    private static boolean isEmptyRagOutput(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String trimmed = text.strip();
        return NO_HIT_HEADER.matcher(trimmed).find();
    }

    private static int parseHitCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = HIT_COUNT.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private static List<String> parseSources(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher fragment = FRAGMENT_DOC.matcher(text);
        while (fragment.find()) {
            addDocName(names, fragment.group(1));
        }
        if (!names.isEmpty()) {
            return new ArrayList<>(names);
        }
        Matcher docLine = SOURCE_DOC_LINE.matcher(text);
        if (docLine.find()) {
            splitDocNames(names, docLine.group(1));
        }
        if (names.isEmpty()) {
            Matcher summaryLine = SOURCE_SUMMARY_LINE.matcher(text);
            if (summaryLine.find()) {
                splitDocNames(names, summaryLine.group(1));
            }
        }
        return new ArrayList<>(names);
    }

    private static void splitDocNames(LinkedHashSet<String> names, String raw) {
        for (String part : raw.split("、")) {
            addDocName(names, part);
        }
    }

    private static void addDocName(LinkedHashSet<String> names, String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DOC_NAME_LEN) {
            return;
        }
        if (trimmed.contains("#") || trimmed.contains("|") || trimmed.contains("---")) {
            return;
        }
        names.add(trimmed);
    }
}
