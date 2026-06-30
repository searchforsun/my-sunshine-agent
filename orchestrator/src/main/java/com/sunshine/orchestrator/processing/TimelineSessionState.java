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
    int pendingToolCalls;
    String lastCompletedToolDisplayName;
    String currentToolStepId;
    String traceMessageId;
    final Map<String, Integer> ragRewriteBaselineByStep = new LinkedHashMap<>();
    final ContentSegmentCoordinator contentSegments = new ContentSegmentCoordinator();
    final List<StreamToken> auxiliaryTokens = new ArrayList<>();
    final Map<String, String> stepDisplayNames = new LinkedHashMap<>();
}
