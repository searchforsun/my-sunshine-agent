package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.HarnessAgentHolder;
import com.sunshine.orchestrator.agent.ProcessingStepMiddleware;
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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
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

    private final HarnessAgentHolder agentHolder;
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
            int checkpointThinkIter = request.checkpointThinkIteration();
            if (checkpointThinkIter > 0) {
                session.resumeFromCheckpoint(checkpointThinkIter);
                log.info("[AgentRuntime] resume from checkpoint userId={} msg={} thinkIter={}",
                        request.userId(), assistantMessageId, checkpointThinkIter);
            }
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
            // P2-1（E5）：指纹缓存取实例。SUB 的 spawn 单独取消句柄绑定本轮实例；
            // 缓存复用下旧 run 已 cancel 的句柄不迁移——新 run 会经 register+bindAgent 覆盖
            HarnessAgent agent = agentHolder.get(request);
            if (request.role() == AgentRole.SUB) {
                SpawnRunRegistry registry = spawnRunRegistry.getIfAvailable();
                if (registry != null) {
                    registry.bindAgent(request.runId(), agent);
                }
            }
            RuntimeContext rt = RuntimeContext.builder()
                    .userId(request.userId())
                    .sessionId(assistantMessageId)
                    .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, bridgeId)
                    .build();
            return agent.streamEvents(inputs, rt)
                    .flatMap(agentEvent -> {
                        // 不在订阅时捕获 epoch 做流级过滤：恢复续跑会 bumpStreamEpoch 抬高 epoch，
                        // 订阅时捕获的旧 epoch 会让本轮所有 drain 失效（子 Agent 步骤卡到主 Agent drain）。
                        // 旧 run 写新流的防护由 GenerationJob.isStreamEpochValid / generationFlush 绑定 epoch 兜底。
                        // reasoning/content delta 经 bridge 路由进 hookQueue 由 runtime 统一 drain（不直灌 SSE）
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
                                session, answerContentStarted, answerStreamFinished, request, answerContent.toString(), agent));
                        return Flux.fromIterable(tail);
                    }))
                    .doFinally(sig -> {
                        // 用户主动取消(CANCEL)与系统异常中断(ON_ERROR)都保存 checkpoint，保证续跑可断点续传
                        if (request.role() == AgentRole.MAIN
                                && (sig == reactor.core.publisher.SignalType.CANCEL
                                    || sig == reactor.core.publisher.SignalType.ON_ERROR)
                                && request.assistantMessageId() != null
                                && !request.assistantMessageId().isBlank()
                                && request.userId() != null) {
                            try {
                                if (agent.getDelegate() != null) {
                                    agent.getDelegate().saveAgentState(request.userId(), request.assistantMessageId());
                                    log.info("[AgentRuntime] checkpoint saved on {} userId={} msg={}",
                                            sig, request.userId(), request.assistantMessageId());
                                }
                            } catch (Exception e) {
                                // checkpoint 保存失败 = 断点续传数据丢失（一致性问题），需 error 级可告警
                                log.error("[AgentRuntime] saveCheckpoint failed userId={} msg={}",
                                        request.userId(), request.assistantMessageId(), e);
                            }
                        }
                        try {
                            sandboxSessionLifecycle.closeQuietly(request);
                        } catch (Exception e) {
                            log.warn("[AgentRuntime] closeSandbox failed: {}", e.getMessage());
                        }
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
            String answerContent,
            HarnessAgent agent) {
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
                taskBoardService.finalizeNativeTimeline(
                        session, request,
                        agent.getDelegate().getAgentState(request.userId(), request.assistantMessageId()));
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
        // AS2 streamEvents：reasoning/content delta 经 bridge 写 hookQueue，runtime 统一 drain。
        if (ev instanceof ThinkingBlockDeltaEvent t) {
            StepEventBridge.emitReasoningChunk(bridgeId, t.getDelta());
        } else if (ev instanceof TextBlockDeltaEvent d) {
            StepEventBridge.emitReasoningContentChunk(bridgeId, d.getDelta());
        }
        // ToolCall/ToolResult 事件由 ProcessingStepMiddleware.onActing 驱动（不经此处）
    }
}
