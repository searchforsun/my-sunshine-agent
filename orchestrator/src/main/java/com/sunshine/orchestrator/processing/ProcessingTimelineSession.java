package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ToolResultSummarizer;
import com.sunshine.orchestrator.routing.ExecutionPlan;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * ReAct 时间线会话：think 边界对齐 AgentScope PreReasoning/PostReasoning Hook，工具步骤由 PreActing/PostActing 写入。
 */
public final class ProcessingTimelineSession {

    private final TimelineAggregator aggregator = new TimelineAggregator();
    private Consumer<ProcessingStep> onStepChanged = s -> {};
    private ProcessingStep lastEmitted;
    private String userQuery;
    private String activeStepId;
    /** ReAct 推理轮次，每轮独立 think 步骤 */
    private int thinkIteration;
    private String currentThinkId;
    /** 工具执行中的并发计数（仅统计） */
    private int pendingToolCalls;
    /** 最近一次完成的工具步骤（供 think-2+ 摘要引用） */
    private String lastCompletedToolDisplayName;
    /** 当前 PreActing 对应的工具步骤 id */
    private String currentToolStepId;
    /** 关联 QueryRewriteTrace 的 assistant messageId */
    private String traceMessageId;
    /** RAG 步骤开始时 trace 条数水位，complete 时仅拼接此后新增的改写 */
    private final Map<String, Integer> ragRewriteBaselineByStep = new java.util.LinkedHashMap<>();

    private final Map<String, String> stepDisplayNames = new java.util.LinkedHashMap<>();

    /** Workflow 节点：绑定 node-{id} 步骤的中文展示名（避免 正在n3） */
    public void bindStepDisplayName(String stepId, String displayName) {
        if (stepId == null || displayName == null || displayName.isBlank()) {
            return;
        }
        String name = displayName.strip();
        stepDisplayNames.put(stepId, name);
        aggregator.bindLabel(stepId, name);
    }

    public void bindTraceMessageId(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            this.traceMessageId = messageId.strip();
        }
    }

    public void recordToolCompleted(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.lastCompletedToolDisplayName = displayName.strip();
        }
    }

    /** PreActing：每次调用新开工具步骤（id 含 @调用时刻） */
    public String beginToolStep(String baseStepId, String phase) {
        long startedAt = System.currentTimeMillis();
        currentToolStepId = ToolStepIds.forInvocation(baseStepId, startedAt);
        pending(currentToolStepId, phase);
        startAt(currentToolStepId, phase, startedAt);
        return currentToolStepId;
    }

    /** PostActing：结束当前工具步骤 */
    public void completeToolStep(String detail) {
        long endedAt = System.currentTimeMillis();
        if (currentToolStepId == null) {
            return;
        }
        completeAt(currentToolStepId, detail, endedAt);
        currentToolStepId = null;
    }

    public String activeStepId() {
        return activeStepId;
    }

    public void appendDelta(String channel, String text) {
        if (activeStepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        appendDelta(activeStepId, channel, text);
    }

    /** 向指定步骤写入增量（workflow 节点完成前写入 result 等） */
    public void appendDelta(String stepId, String channel, String text) {
        if (stepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        long ts = System.currentTimeMillis();
        aggregator.appendDelta(stepId, channel, text, ts);
    }

    public void bindUserQuery(String query) {
        if (query != null && !query.isBlank()) {
            this.userQuery = query.strip();
        }
    }

    public String userQuery() {
        return userQuery;
    }

    public void onStepChanged(Consumer<ProcessingStep> listener) {
        this.onStepChanged = listener != null ? listener : s -> {};
    }

    /** 追加监听，不覆盖已有 listener（供 Hook 异步步骤使用） */
    public void addStepListener(Consumer<ProcessingStep> listener) {
        if (listener == null) {
            return;
        }
        Consumer<ProcessingStep> prev = this.onStepChanged;
        this.onStepChanged = s -> {
            prev.accept(s);
            listener.accept(s);
        };
    }

    Consumer<ProcessingStep> currentListener() {
        return onStepChanged;
    }

    public boolean hasStep(String stepId) {
        return aggregator.get(stepId).isPresent();
    }

    public void pending(String stepId, String phase) {
        apply(stepId, phase, EventKind.PENDING, resolveBefore(stepId), null);
    }

    public void start(String stepId, String phase) {
        startAt(stepId, phase, System.currentTimeMillis());
    }

    public void startAt(String stepId, String phase, long startedAt) {
        if (stepId.equals(activeStepId)) {
            ProcessingStep current = aggregator.get(stepId).orElse(null);
            if (current != null && "running".equals(current.lifecycle())) {
                return;
            }
        } else {
            completeRunningActive(startedAt);
        }
        activeStepId = stepId;
        captureRagRewriteBaseline(stepId);
        applyAt(stepId, phase, EventKind.START, resolveActive(stepId), null, startedAt);
    }

    private void completeRunningActive(long endedAt) {
        if (activeStepId == null) {
            return;
        }
        // think 仅由 PostReasoning 结束，切到工具步骤时不抢先 complete
        if (ThinkStepIds.isThinkStep(activeStepId)) {
            return;
        }
        aggregator.get(activeStepId).ifPresent(step -> {
            if ("running".equals(step.lifecycle())) {
                completeAt(activeStepId, step.detail(), endedAt);
            }
        });
    }

    public void progress(String stepId, String activeSummary) {
        apply(stepId, null, EventKind.PROGRESS, activeSummary, null);
    }

    public void complete(String stepId, String detail) {
        completeAt(stepId, detail, System.currentTimeMillis());
    }

    /** 意图步完成：主行 after 展示路由结论；改写信息写入 detail/metadata 供展开与审计 */
    public void completeIntent(ExecutionPlan plan) {
        completeIntent(plan, com.sunshine.orchestrator.rewrite.QueryRewriteTrace.intentOutcome(traceMessageId).orElse(null));
    }

    public void completeIntent(ExecutionPlan plan, com.sunshine.orchestrator.rewrite.QueryRewriteOutcome intentRewrite) {
        String after = IntentLabels.intentAfterForPlan(userQuery, plan);
        StepMetadata metadata = StepMetadata.mergeRouting(
                StepMetadata.fromRewrite(intentRewrite), plan);
        String detail = intentRewrite != null ? intentRewrite.timelineDetail() : null;
        applyAt("intent", null, EventKind.COMPLETE, after, detail, metadata, System.currentTimeMillis());
        if ("intent".equals(activeStepId)) {
            activeStepId = null;
        }
    }

    /** plan 步完成：写入 Planner 前 query 改写 metadata，供 DAG「开始」节点抽屉展示 */
    public void completePlanAt(String after, String detail, long endedAt) {
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome plannerRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.plannerOutcome(traceMessageId).orElse(null);
        StepMetadata metadata = StepMetadata.fromRewrite(plannerRewrite);
        applyAt("plan", null, EventKind.COMPLETE, after, detail, metadata, endedAt);
        if ("plan".equals(activeStepId)) {
            activeStepId = null;
        }
    }

    /** L0 Skill 绑定：intent 之后插入一步，主行展示「加载技能: @id 展示名」 */
    public void completeSkillLoad(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        long ts = System.currentTimeMillis();
        pending("skill", "skill");
        startAt("skill", "skill", ts);
        String after = SkillLoadLabels.after(skillId.strip());
        StepMetadata metadata = StepMetadata.fromSkillLoad(skillId.strip());
        applyAt("skill", "skill", EventKind.COMPLETE, after, null, metadata, ts);
        if ("skill".equals(activeStepId)) {
            activeStepId = null;
        }
    }

    public void completeAt(String stepId, String detail, long endedAt) {
        completeAt(stepId, detail, detail, endedAt);
    }

    /** @param summaryLine 主行 after 摘要；expandDetail 为展开区正文（可与主行不同） */
    public void completeAt(String stepId, String summaryLine, String expandDetail, long endedAt) {
        StepMetadata metadata = null;
        if (summaryLine != null && (ToolStepIds.isRagStep(stepId) || isWorkflowRagNode(stepId))) {
            String ragInput = summaryLine;
            if (ToolStepIds.isRagStep(stepId) && containsRawRagBody(summaryLine)) {
                ragInput = ToolResultSummarizer.summarize("search_knowledge", summaryLine);
            }
            metadata = StepMetadata.fromRagToolOutput(summaryLine, ragInput);
        }
        String after = resolveAfter(stepId, summaryLine, metadata);
        Integer baseline = ragRewriteBaselineByStep.remove(stepId);
        int rewriteFromIndex = baseline != null ? baseline : 0;
        String rewriteDetail = com.sunshine.orchestrator.rewrite.QueryRewriteTrace
                .combinedRagTimelineDetailSince(traceMessageId, rewriteFromIndex);
        String storedDetail;
        if (ToolStepIds.isRagStep(stepId) || isWorkflowRagNode(stepId)) {
            metadata = mergeRagRewriteMetadataSince(metadata, rewriteFromIndex);
            storedDetail = resolveRagStoredDetail(stepId, summaryLine, rewriteDetail);
            if (rewriteDetail != null && !rewriteDetail.isBlank()) {
                metadata = StepMetadata.withRagExpandLayout(metadata);
            }
        } else {
            storedDetail = expandDetail;
        }
        applyAt(stepId, null, EventKind.COMPLETE, after, storedDetail, metadata, endedAt);
        if (stepId.equals(activeStepId)) {
            activeStepId = null;
        }
    }

    private String resolveBefore(String stepId) {
        if (stepId != null && stepId.startsWith("node-")) {
            String name = stepDisplayNames.get(stepId);
            if (name != null && !name.isBlank()) {
                return "准备" + name;
            }
        }
        return userQuery != null
                ? StepSummarizer.before(stepId, userQuery, lastCompletedToolDisplayName)
                : StepSummarizer.beforeFallback(stepId);
    }

    private String resolveActive(String stepId) {
        if (stepId != null && stepId.startsWith("node-")) {
            String name = stepDisplayNames.get(stepId);
            if (name != null && !name.isBlank()) {
                return "正在" + name;
            }
        }
        return userQuery != null
                ? StepSummarizer.active(stepId, userQuery, lastCompletedToolDisplayName)
                : StepSummarizer.activeFallback(stepId);
    }

    private String resolveAfter(String stepId, String detail) {
        return resolveAfter(stepId, detail, null);
    }

    private String resolveAfter(String stepId, String detail, StepMetadata metadata) {
        if (userQuery != null && metadata != null && !metadata.isEmpty()
                && (ToolStepIds.isRagStep(stepId) || isWorkflowRagNode(stepId))) {
            return StepSummarizer.afterRag(userQuery, detail, metadata);
        }
        return userQuery != null
                ? StepSummarizer.after(stepId, userQuery, detail, lastCompletedToolDisplayName)
                : StepSummarizer.afterFallback(stepId, detail);
    }

    public void fail(String stepId, String detail) {
        apply(stepId, null, EventKind.FAIL, detail, detail);
    }

    public void skip(String stepId, String afterSummary) {
        apply(stepId, null, EventKind.SKIP, afterSummary, null);
    }

    /** 当前 ReAct 推理轮对应的 think 步骤 id（可能为 null） */
    public String currentThinkStepId() {
        return currentThinkId;
    }

    /** 开启新一轮 think；若当前轮仍在 running 则复用 */
    public String openNextThink() {
        if (currentThinkId != null && isStepRunning(currentThinkId)) {
            return currentThinkId;
        }
        thinkIteration++;
        currentThinkId = ThinkStepIds.forIteration(thinkIteration);
        pending(currentThinkId, "think");
        start(currentThinkId, "think");
        return currentThinkId;
    }

    public boolean isThinkRunning() {
        if (currentThinkId != null && isStepRunning(currentThinkId)) {
            return true;
        }
        return snapshot().stream()
                .anyMatch(s -> ThinkStepIds.isThinkStep(s.id()) && isStepRunning(s.id()));
    }

    /** AgentScope PreReasoning：开启本轮 think */
    public void beginReasoningRound() {
        if (isThinkRunning()) {
            completeThinkIfRunning();
        }
        openNextThink();
    }

    /** AgentScope PostReasoning：结束本轮 think */
    public void endReasoningRound() {
        completeThinkIfRunning();
    }

    /** PreActing：仅统计工具并发 */
    public void noteToolCallPending() {
        pendingToolCalls++;
    }

    public void noteToolCallDone() {
        if (pendingToolCalls > 0) {
            pendingToolCalls--;
        }
    }

    public boolean hasPendingToolCalls() {
        return pendingToolCalls > 0;
    }

    /** @deprecated 使用 {@link #endReasoningRound()} */
    public void completeReasoningRound() {
        endReasoningRound();
    }

    /** 结束当前 running 的 think 步骤 */
    public void completeThinkIfRunning() {
        if (currentThinkId != null && isStepRunning(currentThinkId)) {
            complete(currentThinkId, null);
            return;
        }
        snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .ifPresent(id -> complete(id, null));
    }

    public void openThinkParallel() {
        openNextThink();
    }

    public void completeThinkParallelAt(long endedAt) {
        if (currentThinkId != null && isStepRunning(currentThinkId)) {
            completeAt(currentThinkId, null, endedAt);
            return;
        }
        snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .ifPresent(id -> completeAt(id, null, endedAt));
    }

    private boolean isStepRunning(String stepId) {
        return aggregator.get(stepId)
                .map(step -> "running".equals(step.lifecycle()))
                .orElse(false);
    }

    public List<ProcessingStep> snapshot() {
        return aggregator.snapshot();
    }

    public Optional<ProcessingStep> lastChanged() {
        return Optional.ofNullable(lastEmitted);
    }

    private void apply(String stepId, String phase, EventKind kind, String summary, String detail) {
        applyAt(stepId, phase, kind, summary, detail, null, System.currentTimeMillis());
    }

    private void applyAt(String stepId, String phase, EventKind kind, String summary, String detail, long ts) {
        applyAt(stepId, phase, kind, summary, detail, null, ts);
    }

    private void applyAt(
            String stepId, String phase, EventKind kind, String summary, String detail,
            StepMetadata metadata, long ts) {
        ProcessingStep prev = aggregator.get(stepId).orElse(null);
        String effectivePhase = phase != null ? phase : prev != null ? prev.phase() : stepId;
        aggregator.apply(new ProcessingEvent(stepId, effectivePhase, kind, summary, ts, detail, metadata));
        ProcessingStep next = aggregator.get(stepId).orElseThrow();
        // 终态事件必须下发 SSE，避免 sameState 误判导致 node-* 永远停在 running
        if (!sameState(prev, next) || isTerminalEvent(kind)) {
            lastEmitted = next;
            onStepChanged.accept(next);
        }
    }

    private static boolean isTerminalEvent(EventKind kind) {
        return kind == EventKind.COMPLETE || kind == EventKind.FAIL || kind == EventKind.SKIP;
    }

    private static boolean sameState(ProcessingStep prev, ProcessingStep next) {
        if (prev == null) {
            return false;
        }
        return Objects.equals(prev.id(), next.id())
                && Objects.equals(prev.phase(), next.phase())
                && Objects.equals(prev.lifecycle(), next.lifecycle())
                && summaryEquals(prev.summary(), next.summary())
                && Objects.equals(prev.startedAt(), next.startedAt())
                && Objects.equals(prev.endedAt(), next.endedAt())
                && Objects.equals(prev.durationMs(), next.durationMs())
                && Objects.equals(prev.detail(), next.detail())
                && Objects.equals(prev.reasoning(), next.reasoning())
                && Objects.equals(prev.output(), next.output())
                && Objects.equals(prev.result(), next.result())
                && Objects.equals(prev.status(), next.status())
                && Objects.equals(prev.label(), next.label())
                && Objects.equals(prev.metadata(), next.metadata());
    }

    private static boolean summaryEquals(StepSummary a, StepSummary b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.before(), b.before())
                && Objects.equals(a.active(), b.active())
                && Objects.equals(a.after(), b.after());
    }

    /** Hook 已摘要过的 RAG 一行结果不再二次 summarize（避免「命中 3 条」被误判为 0 条） */
    private static boolean containsRawRagBody(String detail) {
        return detail.contains("【")
                || detail.contains("知识库检索结果（共")
                || detail.contains("片段");
    }

    private void captureRagRewriteBaseline(String stepId) {
        if (traceMessageId == null || stepId == null) {
            return;
        }
        if (ToolStepIds.isRagStep(stepId) || isWorkflowRagNode(stepId)) {
            ragRewriteBaselineByStep.put(stepId, com.sunshine.orchestrator.rewrite.QueryRewriteTrace.size(traceMessageId));
        }
    }

    private StepMetadata mergeRagRewriteMetadataSince(StepMetadata metadata, int fromIndex) {
        if (traceMessageId == null) {
            return metadata;
        }
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome ragRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.latestSince(traceMessageId, "rag", fromIndex)
                        .orElse(null);
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome hydeRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.latestSince(traceMessageId, "hyde", fromIndex)
                        .orElse(null);
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome emptyRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.latestSince(traceMessageId, "empty-recall", fromIndex)
                        .orElse(null);
        StepMetadata merged = StepMetadata.mergeRewrite(metadata, ragRewrite);
        merged = StepMetadata.mergeRewrite(merged, hydeRewrite);
        return StepMetadata.mergeRewrite(merged, emptyRewrite);
    }

    private static boolean isWorkflowRagNode(String stepId) {
        return stepId != null && stepId.startsWith("node-rag");
    }

    /** ReAct rag / workflow node-rag：改写走 detail；命中摘要走 summary.after + metadata */
    private static String resolveRagStoredDetail(String stepId, String summaryLine, String rewriteDetail) {
        if (rewriteDetail != null && !rewriteDetail.isBlank()) {
            return rewriteDetail.strip();
        }
        if (isWorkflowRagNode(stepId) && summaryLine != null && !summaryLine.isBlank()) {
            return summaryLine.strip();
        }
        return rewriteDetail;
    }
}
