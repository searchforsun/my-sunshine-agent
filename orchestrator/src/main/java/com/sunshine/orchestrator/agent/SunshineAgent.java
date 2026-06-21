package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sunshine Agent — 封装 AgentScope ReActAgent。
 * 轮次边界与 reasoning 增量由 Hook 产出；Event 流仅承载 AGENT_RESULT 正文。
 */
@Slf4j
@Component
public class SunshineAgent {

    private final ReActAgentFactory agentFactory;
    private final PromptComposer promptComposer;
    private final MemoryProperties memoryProperties;

    public SunshineAgent(ReActAgentFactory agentFactory, PromptComposer promptComposer,
            MemoryProperties memoryProperties) {
        this.agentFactory = agentFactory;
        this.promptComposer = promptComposer;
        this.memoryProperties = memoryProperties;
    }

    @PostConstruct
    public void init() {
        log.info("[Orchestrator] Agent 就绪: 按请求创建 ReActAgent（非单例）");
    }

    public Flux<StreamToken> chat(String userMessage, String userId, String tenantId) {
        return chat(MemoryContext.empty(), userMessage, userId, tenantId, null);
    }

    public Flux<StreamToken> chat(List<ChatTurn> history, String userMessage, String userId, String tenantId) {
        return chat(MemoryContext.empty(), userMessage, userId, tenantId, null);
    }

    public Flux<StreamToken> chat(
            MemoryContext memory, String userMessage, String userId, String tenantId,
            String assistantMessageId) {
        return chat(memory, userMessage, userId, tenantId, assistantMessageId, null);
    }

    public Flux<StreamToken> chat(
            MemoryContext memory, String userMessage, String userId, String tenantId,
            String assistantMessageId, String ragContext) {
        return chat(memory, userMessage, userId, tenantId, assistantMessageId, ragContext, null);
    }

    public Flux<StreamToken> chatAsSubAgent(
            MemoryContext memory, String query, String injectedContext,
            String userId, String tenantId, String assistantMessageId) {
        return chat(memory, query, userId, tenantId, assistantMessageId, null, injectedContext);
    }

    public Flux<StreamToken> chat(
            MemoryContext memory, String userMessage, String userId, String tenantId,
            String assistantMessageId, String ragContext, String financeContext) {

        log.info("[Orchestrator] user={}, ltm={}, mtm={}, stmTurns={}, ragInjected={}, financeInjected={}, msg={}",
                userId,
                memory != null && hasText(memory.ltmSnippet()),
                memory != null && hasText(memory.mtmSnippet()),
                memory != null && memory.stmTurns() != null ? memory.stmTurns().size() : 0,
                ragContext != null && !ragContext.isBlank(),
                financeContext != null && !financeContext.isBlank(),
                userMessage != null && userMessage.length() > 60
                        ? userMessage.substring(0, 60) + "..."
                        : userMessage);

        List<String> injected = new ArrayList<>(2);
        if (ragContext != null && !ragContext.isBlank()) {
            injected.add(ragContext.strip());
        }
        if (financeContext != null && !financeContext.isBlank()) {
            injected.add(financeContext.strip());
        }
        List<Msg> inputs = promptComposer.composeReactInputs(
                PromptComposeRequest.forReact(memory, userMessage, injected));

        StreamOptions options = StreamOptions.builder()
                .incremental(true)
                .eventTypes(EventType.AGENT_RESULT)
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                .includeActingChunk(true)
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery(userMessage);
        session.bindTraceMessageId(assistantMessageId);
        ConcurrentLinkedQueue<StreamToken> hookQueue = new ConcurrentLinkedQueue<>();

        // 子 Agent（Workflow 节点）用独立 bridge id，避免与主流冲突且让 Hook 能写入 timeline
        String bridgeId = assistantMessageId != null ? assistantMessageId : "sub-" + UUID.randomUUID();
        StepEventBridge.bind(bridgeId, session, hookQueue);
        if (assistantMessageId != null) {
            StepEventBridge.setUserQuery(assistantMessageId, userMessage);
        }

        AtomicBoolean generateStarted = new AtomicBoolean(false);
        AtomicBoolean generateCompleted = new AtomicBoolean(false);

        ReActAgent agent = agentFactory.create(bridgeId);
        return agent.stream(inputs, options)
                .flatMap(event -> {
                    List<StreamToken> tokens = new ArrayList<>();
                    tokens.addAll(drainHookTokens(hookQueue));
                    tokens.addAll(mapAgentEvent(event, session, generateStarted));
                    return Flux.fromIterable(tokens);
                })
                .concatWith(Flux.defer(() -> {
                    List<StreamToken> tail = new ArrayList<>(drainHookTokens(hookQueue));
                    tail.addAll(finishGenerateSteps(session, generateStarted, generateCompleted));
                    return Flux.fromIterable(tail);
                }))
                .doFinally(sig -> StepEventBridge.clear(bridgeId))
                .doOnComplete(() -> log.info("[Orchestrator] Agent 流式完成"))
                .doOnError(e -> log.error("[Orchestrator] Agent 异常: {}", e.getMessage(), e));
    }

    private static List<StreamToken> finishGenerateSteps(
            ProcessingTimelineSession session,
            AtomicBoolean generateStarted,
            AtomicBoolean generateCompleted) {

        if (!generateCompleted.compareAndSet(false, true)) {
            return List.of();
        }

        long now = System.currentTimeMillis();

        if (generateStarted.get()) {
            return ProcessingTimelineSupport.run(session, () ->
                    session.completeAt("generate", null, now));
        }

        if (session.isThinkRunning()) {
            return ProcessingTimelineSupport.run(session, session::completeThinkIfRunning);
        }
        return List.of();
    }

    private static List<StreamToken> drainHookTokens(ConcurrentLinkedQueue<StreamToken> hookQueue) {
        List<StreamToken> tokens = new ArrayList<>();
        StreamToken token;
        while ((token = hookQueue.poll()) != null) {
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static List<StreamToken> mapAgentEvent(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean generateStarted) {
        return AgentScopeEventMapper.map(event, session, generateStarted);
    }
}
