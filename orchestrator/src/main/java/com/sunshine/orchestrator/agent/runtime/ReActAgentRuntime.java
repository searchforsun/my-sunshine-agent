package com.sunshine.orchestrator.agent.runtime;

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
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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

    /**
     * Toolkit / ToolManager 拉取含同步 HTTP {@code block()}，禁止在 reactor-http 线程组装，
     * 否则会静默得到空工具集（仅 RAG+沙箱），模型误以为无财务/OA 工具。
     */
    private Flux<StreamToken> runReAct(AgentRunRequest request) {
        AssembledContext memory = request.role() == AgentRole.SUB
                ? AssembledContext.forSubAgent()
                : request.memory();
        String query = request.query();
        int near = memory.nearTurns() != null ? memory.nearTurns().size() : 0;
        int mid = memory.midTurns() != null ? memory.midTurns().size() : 0;
        log.info("[AgentRuntime] role={}, runId={}, user={}, nearTurns={}, midTurns={}, injected={}, skill={}, msg={}",
                request.role(),
                request.runId(),
                request.userId(),
                near,
                mid,
                request.injectedBlocks().size(),
                request.skillId(),
                query != null && query.length() > 60 ? query.substring(0, 60) + "..." : query);
        return Flux.defer(() -> startReActStream(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<StreamToken> startReActStream(AgentRunRequest request) {
        AssembledContext memory = request.role() == AgentRole.SUB
                ? AssembledContext.forSubAgent()
                : request.memory();
        String query = request.query();
        String assistantMessageId = request.assistantMessageId();
        sandboxSessionLifecycle.prepareRun(request);
        try {
        List<Msg> inputs = promptComposer.composeReactInputs(
                PromptComposeRequest.forReact(memory, query, request.skillId(), request.injectedBlocks(),
                        request.reactRestart(), request.reactPromptId()));
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
            return agent.streamEvents(inputs)
                    .flatMap(agentEvent -> {
                        if (epochMessageId != null && runEpoch >= 0
                                && !StepEventBridge.isStreamEpochValid(epochMessageId, runEpoch)) {
                            return Flux.empty();
                        }
                        // delta 事件经 bridge 路由进 hookQueue（与 legacy Hook 一致：reasoning/content 不直灌）
                        routeDeltaToBridge(agentEvent, bridgeId);
                        List<StreamToken> tokens = new ArrayList<>(drainHookTokens(hookQueue));
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
            return Flux.error(e);
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

    private static void routeDeltaToBridge(AgentEvent ev, String bridgeId) {
        // AS2 streamEvents：reasoning/content delta 经 bridge 路由进 hookQueue，
        // 与 legacy Hook 路径一致（emitReasoningChunk/emitReasoningContentChunk 写 hookQueue，runtime drain）。
        if (ev instanceof ThinkingBlockDeltaEvent t) {
            StepEventBridge.emitReasoningChunk(bridgeId, t.getDelta());
        } else if (ev instanceof TextBlockDeltaEvent d) {
            StepEventBridge.emitReasoningContentChunk(bridgeId, d.getDelta());
        }
        // ToolCall/ToolResult 事件由 ProcessingStepMiddleware.onActing 驱动（不经此处）
    }
}
