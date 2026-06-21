package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证多并发 ReAct 会话时 Hook 事件按 bridgeId 精确路由，互不串线 */
class StepEventBridgeConcurrentTest {

    private final List<String> boundIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        boundIds.forEach(StepEventBridge::clear);
        boundIds.clear();
    }

    @Test
    void emit_routesToBoundSessionWhenMultipleActive() {
        ConcurrentLinkedQueue<StreamToken> queue1 = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<StreamToken> queue2 = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<StreamToken> queue3 = new ConcurrentLinkedQueue<>();

        ProcessingTimelineSession session1 = bind("msg-1", queue1);
        ProcessingTimelineSession session2 = bind("msg-2", queue2);
        ProcessingTimelineSession session3 = bind("msg-3", queue3);

        session2.beginReasoningRound();
        StepEventBridge.emitReasoningChunk("msg-2", "仅 msg-2");

        session1.beginReasoningRound();
        StepEventBridge.emitReasoningChunk("msg-1", "仅 msg-1");

        StepEventBridge.emit("msg-3", ProcessingTimelineSession::beginReasoningRound);
        StepEventBridge.emitReasoningChunk("msg-3", "仅 msg-3");

        assertThat(pollReasoning(queue1)).isEqualTo("仅 msg-1");
        assertThat(pollReasoning(queue2)).isEqualTo("仅 msg-2");
        assertThat(pollReasoning(queue3)).isEqualTo("仅 msg-3");
    }

    @Test
    void emit_doesNotCrossContaminateToolSteps() {
        ConcurrentLinkedQueue<StreamToken> queueA = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<StreamToken> queueB = new ConcurrentLinkedQueue<>();
        bind("msg-a", queueA);
        bind("msg-b", queueB);

        StepEventBridge.emit("msg-a", session -> session.beginToolStep("tool-list_finance_messages", "tool"));
        StepEventBridge.emit("msg-a", session -> session.completeToolStep("pending 共 3 条"));
        StepEventBridge.emit("msg-b", session -> session.beginToolStep("tool-search_knowledge", "tool"));
        StepEventBridge.emit("msg-b", session -> session.completeToolStep("命中 2 条"));

        assertThat(lastStepId(drain(queueA))).startsWith("tool-list_finance_messages");
        assertThat(lastStepId(drain(queueB))).startsWith("tool-search_knowledge");
    }

    @Test
    void emitSingleton_stillSkippedWhenMultipleActive() {
        bind("singleton-1", new ConcurrentLinkedQueue<>());
        bind("singleton-2", new ConcurrentLinkedQueue<>());

        ConcurrentLinkedQueue<StreamToken> queue = new ConcurrentLinkedQueue<>();
        ProcessingTimelineSession session = bind("singleton-target", queue);
        session.beginReasoningRound();

        StepEventBridge.emitSingleton(ProcessingTimelineSession::beginReasoningRound);
        StepEventBridge.emitSingletonReasoningChunk("不应出现");

        assertThat(queue.poll()).isNull();
    }

    private ProcessingTimelineSession bind(String id, ConcurrentLinkedQueue<StreamToken> queue) {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        StepEventBridge.bind(id, session, queue);
        boundIds.add(id);
        return session;
    }

    private static String pollReasoning(ConcurrentLinkedQueue<StreamToken> queue) {
        StreamToken token;
        while ((token = queue.poll()) != null) {
            if (token.isStepDelta()) {
                assertThat(token.channel()).isEqualTo("reasoning");
                return token.text();
            }
        }
        throw new AssertionError("未找到 reasoning step_delta");
    }

    private static String lastStepId(List<StreamToken> tokens) {
        return tokens.stream()
                .filter(StreamToken::isStep)
                .map(t -> t.step().id())
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static List<StreamToken> drain(ConcurrentLinkedQueue<StreamToken> queue) {
        List<StreamToken> out = new ArrayList<>();
        StreamToken token;
        while ((token = queue.poll()) != null) {
            out.add(token);
        }
        return out;
    }
}
