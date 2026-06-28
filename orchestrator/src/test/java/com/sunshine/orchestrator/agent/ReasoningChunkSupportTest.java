package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningChunkSupportTest {

    @AfterEach
    void tearDown() {
        StepEventBridge.clear("msg-1");
    }

    @Test
    void extractIncrementalText_fromThinkingBlock() {
        Msg chunk = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(java.util.List.of(ThinkingBlock.builder().thinking("第一步").build()))
                .build();

        assertThat(ReasoningChunkSupport.extractIncrementalText(chunk)).isEqualTo("第一步");
    }

    @Test
    void extractIncrementalContent_fromTextBlock() {
        Msg chunk = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(java.util.List.of(TextBlock.builder().text("我先").build()))
                .build();
        assertThat(ReasoningChunkSupport.extractTextBlockOnly(chunk)).isEqualTo("我先");
    }

    @Test
    void emitReasoningContentChunk_streamsSegmentContent() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("查待办");
        ConcurrentLinkedQueue<StreamToken> queue = new ConcurrentLinkedQueue<>();
        StepEventBridge.bind("msg-1", session, queue);

        session.beginReasoningRound();
        StepEventBridge.emitReasoningContentChunk("msg-1", "你");
        StepEventBridge.emitReasoningContentChunk("msg-1", "好");

        java.util.List<StreamToken> tokens = new java.util.ArrayList<>();
        StreamToken token;
        while ((token = queue.poll()) != null) {
            tokens.add(token);
        }
        assertThat(tokens.stream().filter(StreamToken::isContentStart).count()).isEqualTo(1);
        assertThat(tokens.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("你", "好");
    }

    @Test
    void emitReasoningChunk_enqueuesStepDelta() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("对比财务与 OA");
        ConcurrentLinkedQueue<StreamToken> queue = new ConcurrentLinkedQueue<>();
        StepEventBridge.bind("msg-1", session, queue);

        session.beginReasoningRound();
        StepEventBridge.emitReasoningChunk("msg-1", "pending 共 3 笔");

        StreamToken token = queue.poll();
        assertThat(token).isNotNull();
        assertThat(token.isStepDelta()).isTrue();
        assertThat(token.stepId()).isEqualTo("think");
        assertThat(token.channel()).isEqualTo("reasoning");
        assertThat(token.text()).isEqualTo("pending 共 3 笔");
    }

    @Test
    void emitSingletonReasoningChunk_enqueuesStepDelta() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("对比财务与 OA");
        ConcurrentLinkedQueue<StreamToken> queue = new ConcurrentLinkedQueue<>();
        StepEventBridge.bind("msg-1", session, queue);

        session.beginReasoningRound();
        StepEventBridge.emitSingletonReasoningChunk("pending 共 3 笔");

        StreamToken token = queue.poll();
        assertThat(token).isNotNull();
        assertThat(token.isStepDelta()).isTrue();
        assertThat(token.stepId()).isEqualTo("think");
        assertThat(token.channel()).isEqualTo("reasoning");
        assertThat(token.text()).isEqualTo("pending 共 3 笔");
    }

    @Test
    void emitReasoningChunk_skipsWhenThinkNotRunning() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        ConcurrentLinkedQueue<StreamToken> queue = new ConcurrentLinkedQueue<>();
        StepEventBridge.bind("msg-1", session, queue);

        StepEventBridge.emitReasoningChunk("msg-1", "不应出现");

        assertThat(queue.poll()).isNull();
    }
}
