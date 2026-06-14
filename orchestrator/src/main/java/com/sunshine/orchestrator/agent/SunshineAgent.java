package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamDeltaNormalizer;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sunshine Agent — 封装 AgentScope ReActAgent，复用 Event 流推送步骤与正文
 */
@Slf4j
@Component
public class SunshineAgent {

    private final ReActAgent agent;

    public SunshineAgent(ReActAgent agent) {
        this.agent = agent;
    }

    @PostConstruct
    public void init() {
        log.info("[Orchestrator] Agent 就绪: name={}, maxIters={}",
                agent.getName(), agent.getMaxIters());
    }

    public Flux<StreamToken> chat(String userMessage, String userId, String tenantId) {
        return chat(List.of(), userMessage, userId, tenantId, null);
    }

    public Flux<StreamToken> chat(List<ChatTurn> history, String userMessage, String userId, String tenantId) {
        return chat(history, userMessage, userId, tenantId, null);
    }

    /**
     * 流式对话 — 步骤 token 内联在主流；Hook 异步步骤经 stepQueue 汇入
     */
    public Flux<StreamToken> chat(
            List<ChatTurn> history, String userMessage, String userId, String tenantId,
            String assistantMessageId) {
        return chat(history, userMessage, userId, tenantId, assistantMessageId, null);
    }

    /**
     * @param ragContext 预检索知识库上下文，注入为独立 user 消息（可为 null）
     */
    public Flux<StreamToken> chat(
            List<ChatTurn> history, String userMessage, String userId, String tenantId,
            String assistantMessageId, String ragContext) {

        log.info("[Orchestrator] user={}, history={}, ragInjected={}, msg={}", userId,
                history != null ? history.size() : 0,
                ragContext != null && !ragContext.isBlank(),
                userMessage != null && userMessage.length() > 60
                        ? userMessage.substring(0, 60) + "..."
                        : userMessage);

        List<Msg> inputs = buildInputs(history, userMessage, ragContext);

        StreamOptions options = StreamOptions.builder()
                .incremental(true)
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT, EventType.HINT)
                .includeReasoningChunk(false)
                .includeActingChunk(true)
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery(userMessage);
        ConcurrentLinkedQueue<ProcessingStep> stepQueue = new ConcurrentLinkedQueue<>();
        session.addStepListener(stepQueue::offer);

        if (assistantMessageId != null) {
            StepEventBridge.bind(assistantMessageId, session);
            StepEventBridge.setUserQuery(assistantMessageId, userMessage);
        }

        List<StreamToken> agentBootstrap = ProcessingTimelineSupport.run(session, () -> {
            session.pending("agent", "agent");
            session.start("agent", "agent");
        });

        AtomicBoolean agentCompleted = new AtomicBoolean(false);
        AtomicBoolean generateStarted = new AtomicBoolean(false);
        AtomicBoolean generateCompleted = new AtomicBoolean(false);

        return Flux.concat(
                Flux.fromIterable(agentBootstrap),
                agent.stream(inputs, options)
                        .flatMap(event -> {
                            List<StreamToken> tokens = drainHookSteps(stepQueue);
                            tokens.addAll(mapAgentEvent(event, session, agentCompleted, generateStarted, assistantMessageId));
                            return Flux.fromIterable(tokens);
                        })
                        .transform(StreamDeltaNormalizer::normalizeTokens)
                        .concatWith(Flux.defer(() -> {
                            List<StreamToken> tail = new ArrayList<>(drainHookSteps(stepQueue));
                            tail.addAll(finishAgentGenerateSteps(
                                    session, agentCompleted, generateStarted, generateCompleted,
                                    assistantMessageId));
                            return Flux.fromIterable(tail);
                        }))
        )
                .doFinally(sig -> {
                    if (assistantMessageId != null) {
                        StepEventBridge.clear(assistantMessageId);
                    }
                })
                .doOnComplete(() -> log.info("[Orchestrator] Agent 流式完成"))
                .doOnError(e -> log.error("[Orchestrator] Agent 异常: {}", e.getMessage(), e));
    }

    private static List<StreamToken> finishAgentGenerateSteps(
            ProcessingTimelineSession session,
            AtomicBoolean agentCompleted,
            AtomicBoolean generateStarted,
            AtomicBoolean generateCompleted,
            String assistantMessageId) {

        if (!generateCompleted.compareAndSet(false, true)) {
            return List.of();
        }

        String ragDetail = StepEventBridge.ragDetail(assistantMessageId);
        long now = System.currentTimeMillis();

        if (generateStarted.get()) {
            return ProcessingTimelineSupport.run(session, () ->
                    session.completeAt("generate", null, now));
        }

        if (agentCompleted.compareAndSet(false, true)) {
            return ProcessingTimelineSupport.run(session, () ->
                    session.complete("agent", ragDetail));
        }
        return List.of();
    }

    private static List<StreamToken> drainHookSteps(ConcurrentLinkedQueue<ProcessingStep> stepQueue) {
        List<StreamToken> tokens = new ArrayList<>();
        ProcessingStep step;
        while ((step = stepQueue.poll()) != null) {
            tokens.add(StreamToken.step(step));
        }
        return tokens;
    }

    private static List<Msg> buildInputs(List<ChatTurn> history, String userMessage, String ragContext) {
        List<Msg> inputs = new ArrayList<>();
        if (history != null) {
            for (ChatTurn turn : history) {
                MsgRole role = "assistant".equals(turn.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
                inputs.add(Msg.builder()
                        .role(role)
                        .textContent(turn.content())
                        .build());
            }
        }
        if (ragContext != null && !ragContext.isBlank()) {
            inputs.add(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(ragContext.strip())
                    .build());
        }
        inputs.add(Msg.builder()
                .role(MsgRole.USER)
                .textContent(userMessage)
                .build());
        return inputs;
    }

    private static List<StreamToken> mapAgentEvent(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean agentCompleted,
            AtomicBoolean generateStarted,
            String assistantMessageId) {
        return AgentScopeEventMapper.map(
                event, session, agentCompleted, generateStarted,
                StepEventBridge.ragDetail(assistantMessageId),
                resolveUserQuery(assistantMessageId, session));
    }

    private static String resolveUserQuery(String assistantMessageId, ProcessingTimelineSession session) {
        String fromBridge = StepEventBridge.userQuery(assistantMessageId);
        return fromBridge != null ? fromBridge : session.userQuery();
    }
}
