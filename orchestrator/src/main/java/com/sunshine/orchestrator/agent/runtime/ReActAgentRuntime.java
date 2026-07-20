package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.AgentScopeEventMapper;
import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.agent.SpawnRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardService;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingEvidenceSupport;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** ReAct 模式 Agent 运行时 — MAIN / SUB 共用 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentRuntime implements AgentRuntime {

    private final ReActAgentFactory agentFactory;
    private final PromptComposer promptComposer;
    private final AnswerGroundingChecker groundingChecker;
    private final AgentGroundingProperties groundingProperties;
    private final ReactTaskBoardService taskBoardService;
    private final AgentExecutionProperties executionProperties;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;
    private final ObjectProvider<SpawnRunRegistry> spawnRunRegistry;

    @Override
    public Flux<StreamToken> run(AgentRunRequest request) {
        if (request.role() == AgentRole.PLANNER) {
            return Flux.error(new UnsupportedOperationException(
                    "PLANNER 角色由 PlannerAgentRuntime 实现（Task 3.10.4）"));
        }
        return runReAct(request);
    }

    private Flux<StreamToken> runReAct(AgentRunRequest request) {
        MemoryContext memory = request.role() == AgentRole.SUB
                ? MemoryContext.forSubAgent()
                : request.memory();
        String query = request.query();
        String assistantMessageId = request.assistantMessageId();

        log.info("[AgentRuntime] role={}, runId={}, user={}, stmTurns={}, injected={}, skill={}, msg={}",
                request.role(),
                request.runId(),
                request.userId(),
                memory.stmTurns() != null ? memory.stmTurns().size() : 0,
                request.injectedBlocks().size(),
                request.skillId(),
                query != null && query.length() > 60 ? query.substring(0, 60) + "..." : query);

        sandboxSessionLifecycle.prepareRun(request);
        try {
            List<Msg> inputs = promptComposer.composeReactInputs(
                    PromptComposeRequest.forReact(memory, query, request.skillId(), request.injectedBlocks(),
                            request.reactRestart(), request.reactPromptId()));

            StreamOptions options = StreamOptions.builder()
                    .incremental(true)
                    .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
                    .includeReasoningChunk(true)
                    .includeReasoningResult(false)
                    .includeActingChunk(true)
                    .build();

            ProcessingTimelineSession session = new ProcessingTimelineSession();
            session.bindUserQuery(query);
            session.bindTraceMessageId(assistantMessageId);
            ConcurrentLinkedQueue<StreamToken> hookQueue = new ConcurrentLinkedQueue<>();

            String bridgeId = request.resolveBridgeId();
            StepEventBridge.bind(bridgeId, session, hookQueue);
            if (request.assistantMessageId() != null && !request.assistantMessageId().isBlank()) {
                StepEventBridge.bindHitlBridge(bridgeId, request.assistantMessageId(), true);
                StepEventBridge.setUserQuery(request.assistantMessageId(), query);
                if (request.role() == AgentRole.MAIN) {
                    StepEventBridge.registerMainRun(request.assistantMessageId(), bridgeId);
                }
            } else if (assistantMessageId != null) {
                StepEventBridge.setUserQuery(assistantMessageId, query);
            }

            AtomicBoolean answerContentStarted = new AtomicBoolean(false);
            AtomicBoolean answerStreamFinished = new AtomicBoolean(false);
            StringBuilder answerContent = new StringBuilder();

            ReActAgent agent = agentFactory.create(request);
            if (request.role() == AgentRole.SUB) {
                SpawnRunRegistry registry = spawnRunRegistry.getIfAvailable();
                if (registry != null) {
                    registry.bindAgent(request.runId(), agent);
                }
            }
            final String epochMessageId = assistantMessageId != null && !assistantMessageId.isBlank()
                    ? assistantMessageId.strip() : null;
            final long runEpoch = epochMessageId != null
                    ? StepEventBridge.currentStreamEpoch(epochMessageId) : -1L;
            // sandbox_session SSE：首次 ensureBound（工具调用）时经 StepEventBridge.offerStreamToken 下发
            return agent.stream(inputs, options)
                    .flatMap(event -> {
                        if (epochMessageId != null && runEpoch >= 0
                                && !StepEventBridge.isStreamEpochValid(epochMessageId, runEpoch)) {
                            return Flux.empty();
                        }
                        List<StreamToken> tokens = new ArrayList<>();
                        tokens.addAll(mapAgentEvent(event, session, answerContentStarted));
                        tokens.addAll(drainHookTokens(hookQueue));
                        for (StreamToken token : tokens) {
                            if (token.isContent() && token.text() != null) {
                                answerContent.append(token.text());
                            }
                        }
                        return Flux.fromIterable(tokens);
                    })
                    .concatWith(Flux.defer(() -> {
                        List<StreamToken> tail = new ArrayList<>(drainHookTokens(hookQueue));
                        tail.addAll(finishAnswerStream(
                                session, answerContentStarted, answerStreamFinished, request, answerContent.toString()));
                        return Flux.fromIterable(tail);
                    }))
                    .doFinally(sig -> {
                        sandboxSessionLifecycle.closeQuietly(request);
                        if (request.role() == AgentRole.MAIN
                                && request.assistantMessageId() != null
                                && !request.assistantMessageId().isBlank()) {
                            StepEventBridge.unregisterMainRun(request.assistantMessageId(), bridgeId);
                        }
                        StepEventBridge.clear(bridgeId);
                    })
                    .doOnComplete(() -> log.info("[AgentRuntime] role={} runId={} 完成", request.role(), request.runId()))
                    .doOnError(e -> log.error("[AgentRuntime] role={} runId={} 异常: {}",
                            request.role(), request.runId(), e.getMessage(), e));
        } catch (RuntimeException e) {
            sandboxSessionLifecycle.closeQuietly(request);
            throw e;
        }
    }

    private List<StreamToken> finishAnswerStream(
            ProcessingTimelineSession session,
            AtomicBoolean answerContentStarted,
            AtomicBoolean answerStreamFinished,
            AgentRunRequest request,
            String answerContent) {
        if (!answerStreamFinished.compareAndSet(false, true)) {
            return List.of();
        }
        GroundingVerdict grounding = validateMainGrounding(request, answerContent, session);
        if (grounding != null && !grounding.passed() && answerContentStarted.get()) {
            String anchor = session.contentAnchorAfterStepId();
            if (anchor != null) {
                return ProcessingTimelineSupport.run(session, () -> session.fail(anchor, grounding.reason()));
            }
        }
        return ProcessingTimelineSupport.run(session, () -> {
            session.closeContentSegment();
            session.completeThinkIfRunning();
            if (isTaskboardEnabled(request)) {
                taskBoardService.finalizeTimeline(session, request.assistantMessageId());
            }
        });
    }

    private boolean isTaskboardEnabled(AgentRunRequest request) {
        if (request.role() != AgentRole.MAIN) {
            return false;
        }
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
    }

    private GroundingVerdict validateMainGrounding(
            AgentRunRequest request,
            String answerContent,
            ProcessingTimelineSession session) {
        if (request.role() != AgentRole.MAIN || !groundingProperties.isEnabled()) {
            return null;
        }
        GroundingVerdict verdict = groundingChecker.check(
                answerContent,
                GroundingEvidenceSupport.fromTimeline(
                        session.snapshot(),
                        StepEventBridge.ragDetail(request.assistantMessageId())));
        if (verdict.passed()) {
            return null;
        }
        log.warn("[AgentRuntime] ReAct Grounding 未通过: {}", verdict.reason());
        if (!groundingProperties.isBlockOnFailure()) {
            return null;
        }
        return verdict;
    }

    private static List<StreamToken> drainHookTokens(ConcurrentLinkedQueue<StreamToken> hookQueue) {
        List<StreamToken> tokens = new ArrayList<>();
        StreamToken token;
        while ((token = hookQueue.poll()) != null) {
            tokens.add(token);
        }
        return tokens;
    }

    private static List<StreamToken> mapAgentEvent(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean answerContentStarted) {
        return AgentScopeEventMapper.map(event, session, answerContentStarted);
    }
}
