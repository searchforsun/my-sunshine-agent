package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** ProcessingTimelineSession 可变状态（package-private） */
final class TimelineSessionState {

    final TimelineAggregator aggregator = new TimelineAggregator();
    Consumer<ProcessingStep> onStepChanged = s -> {};
    ProcessingStep lastEmitted;
    String userQuery;
    String activeStepId;
    int thinkIteration;
    String currentThinkId;
    /** 自上次 think 结束以来是否完成过业务 tool（todo_write 不计） */
    boolean toolCompletedSinceLastThink = true;
    /** PostReasoning 刚结束的 think — TaskBoard 首建锚点 */
    String lastCompletedThinkId;
    long lastCompletedThinkEndedAt;
    /**
     * beginReasoningRound 只准备开步意图，首个 ThinkingBlock 再 ensureThinkOpen 落地。
     * NONE=无待开；FRESH=新开；REUSE=复用 lastCompletedThinkId（RESUME）。
     */
    enum PendingThinkOpen { NONE, FRESH, REUSE }
    PendingThinkOpen pendingThinkOpen = PendingThinkOpen.NONE;
    int pendingToolCalls;
    String lastCompletedToolDisplayName;
    String currentToolStepId;
    String traceMessageId;
    final Map<String, Integer> ragRewriteBaselineByStep = new LinkedHashMap<>();
    final ContentSegmentCoordinator contentSegments = new ContentSegmentCoordinator();
    final List<StreamToken> auxiliaryTokens = new ArrayList<>();
    final Map<String, String> stepDisplayNames = new LinkedHashMap<>();
}
