package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSegmentCoordinatorTest {

    @Test
    void ingest_appendsPureIncrementalChunks() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        coordinator.ingest("你", "think", sink::add);
        coordinator.ingest("好", "think", sink::add);
        coordinator.ingest("，世界", "think", sink::add);

        assertThat(sink.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("你", "好", "，世界");
    }

    @Test
    void ingest_emitsStartAndMonotonicChunks() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        coordinator.ingest("你好", "think", sink::add);
        coordinator.ingest("你好，世界", "think", sink::add);

        assertThat(sink.stream().filter(StreamToken::isContentStart).count()).isEqualTo(1);
        assertThat(sink.stream().filter(StreamToken::isContentStart).findFirst().orElseThrow().afterStepId())
                .isEqualTo("think");
        assertThat(sink.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("你好", "，世界");
    }

    @Test
    void ingest_dropsNonMonotonicRestatement() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        String body = "以下是完整的调查结果与合规性分析。\n\n---\n\n## 一、";
        coordinator.ingest(body, "think-3", sink::add);
        int before = sink.size();
        coordinator.ingest("以下是完整的调查结果", "think-3", sink::add);

        assertThat(sink).hasSize(before);
    }

    @Test
    void ingest_dropsFullReportDoubledFromHead() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        String report = "## 待办调查与合规性验证\n\n---\n\n### 一、待办总览\n\n| 类别 | 结果 |";
        String tail = "建议将相关制度录入知识库。";
        String baseline = report + tail;
        coordinator.ingest(baseline, "think-3", sink::add);
        int before = sink.size();
        coordinator.ingest(baseline + report, "think-3", sink::add);

        assertThat(sink).hasSize(before);
    }

    @Test
    void ingest_dropsExactBaselineRepeat() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        String round = "用户要求依次调用三个工具。";
        coordinator.ingest(round, "think", sink::add);
        int before = sink.size();
        coordinator.ingest(round + round, "think", sink::add);

        assertThat(sink).hasSize(before);
    }

    @Test
    void closeIfOpen_emitsContentEnd() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        coordinator.ingest("过渡语", "think", sink::add);
        coordinator.closeIfOpen(new ArrayList<>(), sink::add);

        assertThat(sink.stream().anyMatch(StreamToken::isContentEnd)).isTrue();
    }

    @Test
    void newAnchor_closesPreviousSegment() {
        ContentSegmentCoordinator coordinator = new ContentSegmentCoordinator();
        List<StreamToken> sink = new ArrayList<>();

        coordinator.ingest("第一段", "think", sink::add);
        coordinator.ingest("第二段", "think-2", sink::add);

        assertThat(sink.stream().filter(StreamToken::isContentEnd).count()).isEqualTo(1);
        assertThat(sink.stream().filter(StreamToken::isContentStart).count()).isEqualTo(2);
    }

    @Test
    void ingestStreamingContentDelta_emitsFullTransitionAfterThinkDone() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("查待办");
        session.beginReasoningRound();
        session.endReasoningRound();

        List<StreamToken> out = ProcessingTimelineSupport.run(session, () ->
                session.ingestStreamingContentDelta("好的，我先同时查询 OA 待办和财务待办。"));

        assertThat(out.stream().filter(StreamToken::isContentStart).count()).isEqualTo(1);
        assertThat(out.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("好的，我先同时查询 OA 待办和财务待办。");
    }

    @Test
    void beginReasoningRound_closesOpenContentSegment() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("查待办");
        session.beginReasoningRound();
        session.endReasoningRound();
        ProcessingTimelineSupport.run(session, () ->
                session.ingestStreamingContentDelta("第一轮过渡语。"));

        List<StreamToken> out = ProcessingTimelineSupport.run(session, session::beginReasoningRound);

        assertThat(out.stream().anyMatch(StreamToken::isContentEnd)).isTrue();
    }
}
