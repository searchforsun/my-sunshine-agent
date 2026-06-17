package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamDeltaNormalizer;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.conversation.ConversationScopeHint;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.memory.MemoryMessageBuilder;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.memory.stm.StmBoundaryFormatter;
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
    private final AgentPromptProperties prompts;
    private final MemoryProperties memoryProperties;

    public SunshineAgent(ReActAgent agent, AgentPromptProperties prompts, MemoryProperties memoryProperties) {
        this.agent = agent;
        this.prompts = prompts;
        this.memoryProperties = memoryProperties;
    }

    @PostConstruct
    public void init() {
        log.info("[Orchestrator] Agent 就绪: name={}, maxIters={}",
                agent.getName(), agent.getMaxIters());
    }

    public Flux<StreamToken> chat(String userMessage, String userId, String tenantId) {
        return chat(MemoryContext.empty(), userMessage, userId, tenantId, null);
    }

    public Flux<StreamToken> chat(List<ChatTurn> history, String userMessage, String userId, String tenantId) {
        return chat(MemoryContext.empty(), userMessage, userId, tenantId, null);
    }

    /**
     * 流式对话 — 步骤 token 内联在主流；Hook 异步步骤经 stepQueue 汇入
     */
    public Flux<StreamToken> chat(
            MemoryContext memory, String userMessage, String userId, String tenantId,
            String assistantMessageId) {
        return chat(memory, userMessage, userId, tenantId, assistantMessageId, null);
    }

    /**
     * @param ragContext 预检索知识库上下文，注入为独立 user 消息（可为 null）
     */
    public Flux<StreamToken> chat(
            MemoryContext memory, String userMessage, String userId, String tenantId,
            String assistantMessageId, String ragContext) {
        return chat(memory, userMessage, userId, tenantId, assistantMessageId, ragContext, null);
    }

    /**
     * @param financeContext 预查询财务工具结果，注入为独立 user 消息（可为 null）
     */
    /**
     * Workflow Agent 子节点入口 — injectedContext 注入为预查询上下文（MVP 复用 financeContext 槽位）
     */
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

        List<Msg> inputs = buildInputs(memory, userMessage, ragContext, financeContext);

        StreamOptions options = StreamOptions.builder()
                .incremental(true)
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT, EventType.HINT)
                .includeReasoningChunk(true)
                .includeActingChunk(true)
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery(userMessage);
        ConcurrentLinkedQueue<ProcessingStep> stepQueue = new ConcurrentLinkedQueue<>();

        if (assistantMessageId != null) {
            StepEventBridge.bind(assistantMessageId, session, stepQueue);
            StepEventBridge.setUserQuery(assistantMessageId, userMessage);
        }

        AtomicBoolean generateStarted = new AtomicBoolean(false);
        AtomicBoolean generateCompleted = new AtomicBoolean(false);

        return agent.stream(inputs, options)
                .flatMap(event -> {
                    List<StreamToken> tokens = drainHookSteps(stepQueue);
                    tokens.addAll(mapAgentEvent(event, session, generateStarted, assistantMessageId));
                    return Flux.fromIterable(tokens);
                })
                .transform(StreamDeltaNormalizer::normalizeTokens)
                .concatWith(Flux.defer(() -> {
                    List<StreamToken> tail = new ArrayList<>(drainHookSteps(stepQueue));
                    tail.addAll(finishGenerateSteps(
                            session, generateStarted, generateCompleted));
                    return Flux.fromIterable(tail);
                }))
                .doFinally(sig -> {
                    if (assistantMessageId != null) {
                        StepEventBridge.clear(assistantMessageId);
                    }
                })
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
            return ProcessingTimelineSupport.run(session, () -> session.complete("think", null));
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

    private List<Msg> buildInputs(
            MemoryContext memory, String userMessage, String ragContext, String financeContext) {
        MemoryContext ctx = memory != null ? memory : MemoryContext.empty();
        List<Msg> inputs = new ArrayList<>();
        if (memoryProperties != null && memoryProperties.getLayerPrompt() != null
                && !memoryProperties.getLayerPrompt().isBlank()) {
            inputs.add(Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .textContent(memoryProperties.getLayerPrompt().strip())
                    .build());
        }
        addSystemMsg(inputs, ctx.ltmSnippet());
        addSystemMsg(inputs, ctx.mtmSnippet());
        appendStmTurns(inputs, ctx);
        if (ragContext != null && !ragContext.isBlank()) {
            inputs.add(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(ragContext.strip())
                    .build());
        }
        if (financeContext != null && !financeContext.isBlank()) {
            inputs.add(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(financeContext.strip())
                    .build());
        }
        ConversationScopeHint.resolve(prompts).ifPresent(scope -> inputs.add(Msg.builder()
                .role(MsgRole.SYSTEM)
                .textContent(scope)
                .build()));
        inputs.add(Msg.builder()
                .role(MsgRole.USER)
                .textContent(MemoryMessageBuilder.formatCurrentUser(userMessage, memoryProperties))
                .build());
        return inputs;
    }

    private void appendStmTurns(List<Msg> inputs, MemoryContext memory) {
        if (memory.stmTurns() == null || memory.stmTurns().isEmpty()) {
            return;
        }
        String boundary = StmBoundaryFormatter.format(memoryProperties);
        if (boundary != null && !boundary.isBlank()) {
            inputs.add(Msg.builder().role(MsgRole.SYSTEM).textContent(boundary.strip()).build());
        }
        for (ChatTurn turn : memory.stmTurns()) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            MsgRole role = "assistant".equals(turn.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
            inputs.add(Msg.builder().role(role).textContent(turn.content()).build());
        }
    }

    private static void addSystemMsg(List<Msg> inputs, String text) {
        if (text != null && !text.isBlank()) {
            inputs.add(Msg.builder().role(MsgRole.SYSTEM).textContent(text.strip()).build());
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static List<StreamToken> mapAgentEvent(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean generateStarted,
            String assistantMessageId) {
        return AgentScopeEventMapper.map(
                event, session, generateStarted,
                StepEventBridge.ragDetail(assistantMessageId),
                resolveUserQuery(assistantMessageId, session));
    }

    private static String resolveUserQuery(String assistantMessageId, ProcessingTimelineSession session) {
        String fromBridge = StepEventBridge.userQuery(assistantMessageId);
        return fromBridge != null ? fromBridge : session.userQuery();
    }
}
