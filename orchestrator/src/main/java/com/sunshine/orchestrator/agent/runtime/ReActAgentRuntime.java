package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.agent.DecisionResumeOutcome;
import com.sunshine.orchestrator.agent.DecisionResumeSupport;
import com.sunshine.orchestrator.agent.HarnessAgentHolder;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMiddleware;
import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.agent.ReActSystemPromptResolver;
import com.sunshine.orchestrator.agent.SpawnRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.context.ModelWindowCache;
import com.sunshine.orchestrator.execution.DecisionResumeSteps;
import com.sunshine.orchestrator.hitl.HitlWaitInterruptedException;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.taskboard.TaskBoardService;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingEvidenceSupport;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.processing.ContentBlocksJson;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.prompt.ComposedReactInputs;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import com.sunshine.orchestrator.sandbox.SandboxWriteEditPlaceholderSupport;
import com.sunshine.orchestrator.sandbox.SandboxWriteHitlMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** ReAct 模式 Agent 运行时 — MAIN / SUB / WORKER 共用；PLANNER 由 Facade 路由 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentRuntime implements AgentRuntime {

    private final HarnessAgentHolder agentHolder;
    private final PromptComposer promptComposer;
    private final AnswerGroundingChecker groundingChecker;
    private final AgentGroundingProperties groundingProperties;
    private final TaskBoardService taskBoardService;
    private final AgentExecutionProperties executionProperties;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;
    private final ChatConversationRepository conversationRepo;
    private final ObjectProvider<SpawnRunRegistry> spawnRunRegistry;
    private final ObjectProvider<DecisionResumeSupport> decisionResumeSupport;
    private final SandboxWriteEditPlaceholderSupport writeEditPlaceholder;
    private final ReActSystemPromptResolver systemPromptResolver;
    private final ModelWindowCache modelWindowCache;
    private final ModelSceneResolver modelSceneResolver;
    private final ChatMessageRepository messageRepo;

    @Override
    public Flux<StreamToken> run(AgentRunRequest request) {
        if (request.role() == AgentRole.PLANNER) {
            return Flux.error(new UnsupportedOperationException(
                    "PLANNER 角色由 PlannerAgentRuntime 实现（ReAct + planner.harness）"));
        }
        return runReAct(request);
    }

    /** Facade / PlannerAgentRuntime：PLANNER 走 ReAct 流；{@link #run} 仍拒绝直调以免绕过 Facade。 */
    public Flux<StreamToken> runPlannerReAct(AgentRunRequest request) {
        if (request == null || request.role() != AgentRole.PLANNER) {
            return Flux.error(new IllegalArgumentException("runPlannerReAct 仅接受 PLANNER"));
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
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    private Flux<StreamToken> startReActStream(AgentRunRequest request) {
        AssembledContext memory = request.role() == AgentRole.SUB
                ? AssembledContext.forSubAgent()
                : request.memory();
        String query = request.query();
        String assistantMessageId = request.assistantMessageId();
        sandboxSessionLifecycle.prepareRun(request);
        try {
        // 场景覆盖层：会话 kind 从 request 透传（不查库）；workspace checkout 目录仅 MAIN 需查库
        String convKind = request.conversationKind();
        String workspaceCheckout = null;
        if (request.role() == AgentRole.MAIN && StringUtils.hasText(request.conversationId())) {
            ChatConversationEntity conv = conversationRepo.findById(request.conversationId()).orElse(null);
            if (conv != null) {
                workspaceCheckout = conv.getCheckoutPath();
            }
        }
            ProcessingTimelineSession session = new ProcessingTimelineSession();
            session.bindUserQuery(query);
            session.bindTraceMessageId(assistantMessageId);
            // 续跑：抬高 content 段号，避免 content-1 与前端残留块撞车导致新正文灌进旧锚点
            if (assistantMessageId != null) {
                String blocksJson = StepEventBridge.peekResumeContentBlocks(assistantMessageId);
                int maxSeq = ContentBlocksJson.maxContentSegmentSeq(blocksJson);
                if (maxSeq > 0) {
                    session.contentSegments().seedSeq(maxSeq);
                }
            }
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
                StepEventBridge.bindHitlBridge(bridgeId, request.assistantMessageId(), resolveHitlEnabled(request));
                // 仅当请求显式携带写跳过模式时才绑定；否则保留 ChatController 按 writeHitlMode 已绑定的值，
                // 避免 main request permissionsJson 为空时用 NEVER 覆盖用户选择的 always/smart。
                SandboxWriteHitlMode writeMode = resolveWriteHitlMode(request);
                if (writeMode != null) {
                    StepEventBridge.bindWriteHitlMode(request.assistantMessageId(), writeMode);
                }
                StepEventBridge.setUserQuery(request.assistantMessageId(), query);
                if (request.role() == AgentRole.MAIN || request.role() == AgentRole.PLANNER) {
                    // PLANNER 亦注册 main run：await_tool_run 依赖 activeMainBridge 判定「仅主 Agent 可调用」，
                    // Planner 是 pro 模式的执行主链，须经 await_tool_run 收集 Worker handoff。
                    StepEventBridge.registerMainRun(request.assistantMessageId(), bridgeId);
                }
            } else if (assistantMessageId != null) {
                StepEventBridge.setUserQuery(assistantMessageId, query);
            }
            // 续跑 decision：bridge 就绪后同步 re-await；成功则并入【用户决策】再 compose（不依赖二次 tool_call）。
            // MAIN 经 ReactExecutor reactRestart 时 bind DecisionResumeSteps；PLANNER 经 HarnessPlanner bind（resume 为新的 Planner run）
            List<String> injectedBlocks = new ArrayList<>(request.injectedBlocks());
            if (StringUtils.hasText(request.assistantMessageId())
                    && (request.role() == AgentRole.MAIN || request.role() == AgentRole.PLANNER)) {
                List<ProcessingStep> resumeSteps = DecisionResumeSteps.take(request.assistantMessageId());
                DecisionResumeSupport resumeSupport = decisionResumeSupport.getIfAvailable();
                if (resumeSupport != null && !resumeSteps.isEmpty()) {
                    DecisionResumeOutcome outcome = resumeSupport.prepareOnReactResume(
                            request.assistantMessageId(), bridgeId, resumeSteps);
                    if (outcome.shouldAbort()) {
                        // 对齐 HITL：GenerationJob 将 HitlWaitInterruptedException → INTERRUPTED
                        throw new HitlWaitInterruptedException();
                    }
                    injectedBlocks.addAll(outcome.injectBlocks());
                }
            }
            ComposedReactInputs composed = promptComposer.composeReactInputs(
                    request.role() == AgentRole.PLANNER
                            ? PromptComposeRequest.forPlannerHarness(
                                    memory, query, injectedBlocks, request.reactRestart(),
                                    request.harnessPromptId(), convKind, workspaceCheckout)
                            : PromptComposeRequest.forReact(
                                    memory, query, request.skillId(), injectedBlocks,
                                    request.reactRestart(), null, convKind, workspaceCheckout),
                    systemPromptResolver.resolve(request));
            List<Msg> inputs = composed.inputs();
            Map<String, Integer> contextGroups = new ConcurrentHashMap<>(composed.staticGroups());
            AtomicBoolean answerContentStarted = new AtomicBoolean(false);
            AtomicBoolean answerStreamFinished = new AtomicBoolean(false);
            StringBuilder answerContent = new StringBuilder();
            // P2-1（E5）：指纹缓存取实例。SUB 的 spawn 单独取消句柄绑定本轮实例；
            // 缓存复用下旧 run 已 cancel 的句柄不迁移——新 run 会经 register+bindAgent 覆盖
            // WORKER 同构绑定（v17.7 用户单独取消 worker 卡）
            HarnessAgent agent = agentHolder.get(request);
            if (request.role() == AgentRole.SUB || request.role() == AgentRole.WORKER) {
                SpawnRunRegistry registry = spawnRunRegistry.getIfAvailable();
                if (registry != null) {
                    registry.bindAgent(request.runId(), agent);
                }
            }
            UsageAccumulator seed = seedUsageFromPersisted(assistantMessageId);
            AtomicReference<UsageAccumulator> usageAcc = new AtomicReference<>(seed);
            String resolvedModel = resolveModelName(request);
            // WORKER/SUB 独立 sessionId：同消息多个 Worker/子 Agent 复用同一 HarnessAgent 实例
            // （fingerprint 缓存），若沿用 assistantMessageId 会落入 AgentScope per-instance
            // callGates 的 (userId, sessionId) 串行槽——同一时刻仅一个实例的 LLM 流式输出
            // （前端呈现"一个一个输出"）。改用 runId 槽解除，各自独立并行流式。
            String agentSessionId = request.role() == AgentRole.WORKER
                    ? "worker-" + request.runId()
                    : request.role() == AgentRole.SUB
                            ? "subagent-" + request.runId()
                            : assistantMessageId;
            RuntimeContext rt = RuntimeContext.builder()
                    .userId(request.userId())
                    .sessionId(agentSessionId)
                    .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, bridgeId)
                    .put(ProcessingStepMiddleware.CTX_REACT_MAX_ITERS, resolveMaxIters(request))
                    .put(ProcessingStepMiddleware.CTX_CONTEXT_GROUPS, contextGroups)
                    .build();
            return agent.streamEvents(inputs, rt)
                    .flatMap(agentEvent -> {
                        // 不在订阅时捕获 epoch 做流级过滤：恢复续跑会 bumpStreamEpoch 抬高 epoch，
                        // 订阅时捕获的旧 epoch 会让本轮所有 drain 失效（子 Agent 步骤卡到主 Agent drain）。
                        // 旧 run 写新流的防护由 GenerationJob.isStreamEpochValid / generationFlush 绑定 epoch 兜底。
                        // reasoning/content delta 经 bridge 路由进 hookQueue 由 runtime 统一 drain（不直灌 SSE）
                        routeDeltaToBridge(agentEvent, bridgeId, usageAcc, contextGroups, resolvedModel);
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
                        if ((request.role() == AgentRole.MAIN || request.role() == AgentRole.PLANNER)
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

    private void routeDeltaToBridge(
            AgentEvent ev, String bridgeId,
            AtomicReference<UsageAccumulator> usageAcc,
            Map<String, Integer> groupsSnapshot, String resolvedModel) {
        // AS2 streamEvents：reasoning/content/tool_call delta 经 bridge 写 hookQueue，runtime 统一 drain。
        if (log.isDebugEnabled()) {
            log.debug("[Runtime] routeDeltaToBridge ev={}", ev.getClass().getSimpleName());
        }
        if (ev instanceof ThinkingBlockStartEvent) {
            StepEventBridge.emitReasoningBlockStart(bridgeId);
        } else if (ev instanceof ThinkingBlockDeltaEvent t) {
            StepEventBridge.emitReasoningChunk(bridgeId, t.getDelta());
        } else if (ev instanceof ThinkingBlockEndEvent) {
            // reasoning 通道结束即结掉 think，不等后续 tool_call 参数生成 / onReasoning flux
            StepEventBridge.emitReasoningBlockEnd(bridgeId);
        } else if (ev instanceof ToolCallStartEvent start) {
            writeEditPlaceholder.onToolCallStart(bridgeId, start.getToolCallId(), start.getToolCallName());
        } else if (ev instanceof ToolCallDeltaEvent d) {
            writeEditPlaceholder.onToolCallDelta(
                    bridgeId, d.getToolCallId(), d.getToolCallName(), d.getDelta());
        } else if (ev instanceof ToolCallEndEvent end) {
            writeEditPlaceholder.onToolCallEnd(end.getToolCallId());
        } else if (ev instanceof TextBlockDeltaEvent d) {
            StepEventBridge.emitReasoningContentChunk(bridgeId, d.getDelta());
        } else if (ev instanceof ModelCallEndEvent end) {
            emitUsageToken(end, bridgeId, usageAcc, groupsSnapshot, resolvedModel);
        }
        // ToolResult 事件由 ProcessingStepMiddleware.onActing 驱动（不经此处）
    }

    private void emitUsageToken(ModelCallEndEvent end, String bridgeId,
            AtomicReference<UsageAccumulator> usageAcc,
            Map<String, Integer> groups, String modelName) {
        ChatUsage usage = end.getUsage();
        if (usage == null) {
            return;
        }
        UsageAccumulator next = usageAcc.updateAndGet(acc -> new UsageAccumulator(
                acc.inputTokens() + usage.getInputTokens(),
                acc.outputTokens() + usage.getOutputTokens(),
                acc.cachedTokens() + usage.getCachedTokens(),
                acc.llmCalls() + 1));
        Integer window = resolveContextWindow(modelName);
        StepEventBridge.offerStreamToken(bridgeId, StreamToken.usage(
                UsageJsonSupport.buildUsageWire(next.llmCalls(), usage, next, window, groups)));
    }

    private Integer resolveContextWindow(String modelName) {
        if (modelName == null) {
            return null;
        }
        try {
            return modelWindowCache.windowFor(modelName);
        } catch (Exception e) {
            return null;
        }
    }

    /** 消息级 usage 累计（续跑从落库 usage_json 起算）；package-visible 供 UsageJsonSupport 引用 */
    record UsageAccumulator(long inputTokens, long outputTokens, long cachedTokens, int llmCalls) {
    }

    /** 续跑从落库 usage_json 的 messageUsage 起算，累计跨轮次不失真（spec §4.4） */
    private UsageAccumulator seedUsageFromPersisted(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return new UsageAccumulator(0, 0, 0, 0);
        }
        try {
            return messageRepo.findById(assistantMessageId)
                    .map(m -> UsageJsonSupport.parseAccumulator(m.getUsageJson()))
                    .orElseGet(() -> new UsageAccumulator(0, 0, 0, 0));
        } catch (Exception e) {
            return new UsageAccumulator(0, 0, 0, 0);
        }
    }

    /** 与 ReActAgentFactory.resolveModel 同语义：modelConfigJson.model > modelOverride > scene；解析失败不阻断主流程 */
    private String resolveModelName(AgentRunRequest request) {
        try {
            String fromConfig = ReActAgentFactory.extractModelFromConfigJson(request.modelConfigJson());
            String override = StringUtils.hasText(fromConfig) ? fromConfig : request.modelOverride();
            AgentRole role = request.role();
            if (role == AgentRole.MAIN) {
                if (StringUtils.hasText(fromConfig)) {
                    return modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), fromConfig).effectiveModel();
                }
                return modelSceneResolver.resolveChat(override).effectiveModel();
            }
            if (role == AgentRole.SUB || role == AgentRole.WORKER) {
                return modelSceneResolver.resolve(ModelSceneKey.SUBAGENT.key(), override).effectiveModel();
            }
            return modelSceneResolver.resolve(ModelSceneKey.PLANNER.key(), override).effectiveModel();
        } catch (Exception e) {
            return null;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 ReActAgentFactory.resolveMaxIters 一致：request 显式优先；WORKER 默认 taskMaxIters */
    private int resolveMaxIters(AgentRunRequest request) {
        if (request != null && request.maxIters() > 0) {
            return request.maxIters();
        }
        AgentExecutionProperties.React react = executionProperties.getReact();
        if (react == null) {
            return 0;
        }
        if (request != null && request.role() == AgentRole.WORKER) {
            return react.getTaskMaxIters();
        }
        return react.getMaxIters();
    }

    private static boolean resolveHitlEnabled(AgentRunRequest request) {
        String permissionsJson = request.permissionsJson();
        if (permissionsJson == null || permissionsJson.isBlank() || "{}".equals(permissionsJson)) {
            return true;
        }
        try {
            Map<String, Object> permissions = MAPPER.readValue(permissionsJson, new TypeReference<Map<String, Object>>() {});
            String toolConfirmation = (String) permissions.get("toolConfirmation");
            if ("never".equals(toolConfirmation)) {
                return false;
            }
            if ("always".equals(toolConfirmation)) {
                return true;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从请求权限配置解析写跳过模式；请求未携带（permissionsJson 为空/无 sandboxWriteMode）时返回 null，
     * 表示「不覆盖外部已绑定模式」（如 ChatController 按 writeHitlMode 注入的 always/smart）。
     */
    private static SandboxWriteHitlMode resolveWriteHitlMode(AgentRunRequest request) {
        String permissionsJson = request.permissionsJson();
        if (permissionsJson == null || permissionsJson.isBlank() || "{}".equals(permissionsJson)) {
            return null;
        }
        try {
            Map<String, Object> permissions = MAPPER.readValue(permissionsJson, new TypeReference<Map<String, Object>>() {});
            String sandboxWriteMode = (String) permissions.get("sandboxWriteMode");
            if (sandboxWriteMode == null || sandboxWriteMode.isBlank()) {
                return null;
            }
            return switch (sandboxWriteMode.strip().toLowerCase()) {
                case "never" -> SandboxWriteHitlMode.NEVER;
                case "always" -> SandboxWriteHitlMode.ALWAYS;
                case "smart" -> SandboxWriteHitlMode.SMART;
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
