package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineAggregatorTest {

    @Test
    void pendingStartComplete_producesThreePhaseSummaryAndDuration() {
        TimelineAggregator aggregator = new TimelineAggregator();
        long t0 = 1_000L;
        long t1 = 1_100L;
        long t2 = 1_200L;

        aggregator.apply(new ProcessingEvent("intent", "intent", EventKind.PENDING, "准备识别意图", t0, null, null));
        aggregator.apply(new ProcessingEvent("intent", "intent", EventKind.START, "正在分析用户输入", t1, null, null));
        aggregator.apply(new ProcessingEvent(
                "intent", "intent", EventKind.COMPLETE, "判定为：知识库查询", t2, "知识库查询", null));

        ProcessingStep step = aggregator.get("intent").orElseThrow();
        assertEquals("done", step.lifecycle());
        assertEquals("识别意图", step.label());
        assertEquals("准备识别意图", step.summary().before());
        assertEquals("正在分析用户输入", step.summary().active());
        assertEquals("判定为：知识库查询", step.summary().after());
        assertEquals(t1, step.startedAt());
        assertEquals(t2, step.endedAt());
        assertEquals(100L, step.durationMs());
        assertEquals("知识库查询", step.detail());
        assertEquals(t2, step.ts());
    }

    @Test
    void progress_overwritesActiveSummary() {
        TimelineAggregator aggregator = new TimelineAggregator();
        long t0 = 1_000L;
        long t1 = 1_100L;

        aggregator.apply(new ProcessingEvent("rag", "rag", EventKind.START, "正在查询 Milvus", t0, null, null));
        aggregator.apply(new ProcessingEvent("rag", "rag", EventKind.PROGRESS, "正在查询 Milvus…", t0 + 10, null, null));
        aggregator.apply(new ProcessingEvent("rag", "rag", EventKind.PROGRESS, "命中 3 条", t1, null, null));

        ProcessingStep step = aggregator.get("rag").orElseThrow();
        assertEquals("running", step.lifecycle());
        assertEquals("命中 3 条", step.summary().active());
    }

    @Test
    void replayPreservesEarliestStartedAt() {
        TimelineAggregator aggregator = new TimelineAggregator();
        long earliest = 500L;
        long replay = 800L;

        aggregator.apply(new ProcessingEvent("agent", "agent", EventKind.START, "正在调用 ReActAgent", earliest, null, null));
        aggregator.apply(new ProcessingEvent("agent", "agent", EventKind.START, "正在调用 ReActAgent", replay, null, null));
        aggregator.apply(new ProcessingEvent("agent", "agent", EventKind.COMPLETE, "推理完成", replay + 100, null, null));

        ProcessingStep step = aggregator.get("agent").orElseThrow();
        assertEquals(earliest, step.startedAt());
        assertEquals(replay + 100, step.endedAt());
        assertEquals(400L, step.durationMs());
        assertTrue(step.summary().after().contains("推理完成"));
    }

    @Test
    void complete_clearsRetryRecoveryMetadata() {
        TimelineAggregator aggregator = new TimelineAggregator();
        long t0 = 1_000L;
        NodeRecoveryMeta awaiting = NodeRecoveryMeta.awaiting("tok", "连接失败", t0 + 60_000);
        StepMetadata withRecovery = StepMetadata.withRecovery(null, awaiting);
        aggregator.apply(new ProcessingEvent(
                "node-tool", "node", EventKind.PROGRESS, null, t0, null, withRecovery));
        NodeRecoveryMeta resolved = NodeRecoveryMeta.resolved(NodeRecoveryMeta.STATUS_RETRY, awaiting);
        StepMetadata retryMeta = StepMetadata.withRecovery(withRecovery, resolved);
        aggregator.apply(new ProcessingEvent(
                "node-tool", "node", EventKind.PROGRESS, null, t0 + 100, null, retryMeta));
        aggregator.apply(new ProcessingEvent(
                "node-tool", "node", EventKind.COMPLETE, "工具调用完成", t0 + 200, "ok", null));

        ProcessingStep step = aggregator.get("node-tool").orElseThrow();
        assertEquals("done", step.lifecycle());
        assertTrue(step.metadata() == null || step.metadata().recovery() == null);
    }

    @Test
    void complete_preservesSkippedRecoveryMetadata() {
        TimelineAggregator aggregator = new TimelineAggregator();
        long t0 = 1_000L;
        NodeRecoveryMeta awaiting = NodeRecoveryMeta.awaiting("tok", "连接失败", t0 + 60_000);
        StepMetadata withRecovery = StepMetadata.withRecovery(null, awaiting);
        aggregator.apply(new ProcessingEvent(
                "node-tool", "node", EventKind.PROGRESS, null, t0, null, withRecovery));
        NodeRecoveryMeta skipped = NodeRecoveryMeta.resolved(NodeRecoveryMeta.STATUS_SKIPPED, awaiting);
        StepMetadata skipMeta = StepMetadata.withRecovery(withRecovery, skipped);
        aggregator.apply(new ProcessingEvent(
                "node-tool", "node", EventKind.PROGRESS, null, t0 + 100, null, skipMeta));
        aggregator.apply(new ProcessingEvent(
                "node-tool", "node", EventKind.COMPLETE, "已跳过：连接失败", t0 + 200, "连接失败", null));

        ProcessingStep step = aggregator.get("node-tool").orElseThrow();
        assertEquals("done", step.lifecycle());
        assertEquals(NodeRecoveryMeta.STATUS_SKIPPED, step.metadata().recovery().status());
    }
}
