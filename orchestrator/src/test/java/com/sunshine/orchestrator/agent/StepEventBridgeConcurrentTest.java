package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.agent.SubAgentTimelineBridge;
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
    void drainHookQueue_appliesTokenWrapperForSubAgent() {
        ConcurrentLinkedQueue<StreamToken> queue = new ConcurrentLinkedQueue<>();
        String bridgeId = "sub-run-1";
        bind(bridgeId, queue);
        SubAgentTimelineBridge bridge = new SubAgentTimelineBridge("approve", "审批任务");
        StepEventBridge.bindTokenWrapper(bridgeId, bridge::wrap);

        StepEventBridge.emit(bridgeId, session -> session.beginToolStep("tool-approve_oa_task", "tool"));

        List<StreamToken> out = new ArrayList<>();
        StepEventBridge.drainHookQueueToGeneration(bridgeId, out::add);

        assertThat(out).isNotEmpty();
        assertThat(out).allMatch(t -> t.isStep() && "node-approve".equals(t.step().id()));
        assertThat(out.get(out.size() - 1).step().subSteps()).isNotEmpty();
        assertThat(out.get(out.size() - 1).step().subSteps().get(0).id()).startsWith("tool-approve_oa_task");
    }

    @Test
    void inactiveMainBridge_blocksEmitAndDrain() {
        String assistantId = "msg-main";
        String staleBridge = "main-stale";
        String activeBridge = "main-active";
        ConcurrentLinkedQueue<StreamToken> staleQueue = new ConcurrentLinkedQueue<>();
        bind(staleBridge, staleQueue);
        StepEventBridge.bindHitlBridge(staleBridge, assistantId, true);
        ConcurrentLinkedQueue<StreamToken> activeQueue = new ConcurrentLinkedQueue<>();
        bind(activeBridge, activeQueue);
        StepEventBridge.bindHitlBridge(activeBridge, assistantId, true);
        StepEventBridge.registerMainRun(assistantId, activeBridge);

        StepEventBridge.emit(staleBridge, session -> session.beginToolStep("tool-x", "tool"));
        assertThat(staleQueue.poll()).isNull();

        StepEventBridge.emit(activeBridge, session -> session.beginToolStep("tool-y", "tool"));
        assertThat(lastStepId(drain(activeQueue))).startsWith("tool-y");
    }

    @Test
    void resolveHitlBridgeId_prefersToolUseBindingWhenMultipleSessions() {
        bind("msg-a", new ConcurrentLinkedQueue<>());
        bind("msg-b", new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitl("msg-b", true);
        StepEventBridge.bindToolUseBridge("tool-use-1", "msg-b");

        assertThat(StepEventBridge.bridgeIdForToolUse("tool-use-1")).isEqualTo("msg-b");
        assertThat(StepEventBridge.resolveHitlBridgeId()).isEqualTo("msg-b");

        StepEventBridge.unbindToolUseBridge("tool-use-1");
    }

    @Test
    void resolveHitlBridgeId_fallsBackToSingleHitlBridge() {
        bind("msg-a", new ConcurrentLinkedQueue<>());
        bind("msg-b", new ConcurrentLinkedQueue<>());
        StepEventBridge.bindHitl("msg-b", true);

        assertThat(StepEventBridge.resolveHitlBridgeId()).isEqualTo("msg-b");
    }

    @Test
    void generationFlush_staleEpochDoesNotRouteAfterBump() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        StepEventBridge.bind("msg-stale", session, new ConcurrentLinkedQueue<>());
        boundIds.add("msg-stale");
        List<StreamToken> flushed = new ArrayList<>();
        StepEventBridge.bindGenerationFlush("msg-stale", flushed::add);

        session.beginReasoningRound();
        StepEventBridge.emitReasoningChunk("msg-stale", "旧推理");
        assertThat(flushed).hasSize(1);

        flushed.clear();
        StepEventBridge.bumpStreamEpoch("msg-stale");
        StepEventBridge.emitReasoningChunk("msg-stale", "不应刷入");
        assertThat(flushed).isEmpty();
    }

    @Test
    void generationFlush_routesImmediatelyWithoutQueue() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        StepEventBridge.bind("msg-flush", session, new ConcurrentLinkedQueue<>());
        boundIds.add("msg-flush");
        List<StreamToken> flushed = new ArrayList<>();
        StepEventBridge.bindGenerationFlush("msg-flush", flushed::add);

        session.beginReasoningRound();
        StepEventBridge.emitReasoningChunk("msg-flush", "增量推理");

        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).isStepDelta()).isTrue();
        assertThat(flushed.get(0).text()).isEqualTo("增量推理");
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
