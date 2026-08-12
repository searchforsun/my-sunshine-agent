package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.hitl.HitlParamSupport;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.processing.AwaitToolRunLabels;
import com.sunshine.orchestrator.processing.DecisionLabels;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.ToolExpandDetailSupport;
import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxCancelExpand;
import com.sunshine.orchestrator.sandbox.SandboxEditDiffHolder;
import com.sunshine.orchestrator.sandbox.SandboxIds;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.sandbox.SandboxWriteEditPlaceholderSupport;
import com.sunshine.orchestrator.sandbox.SandboxStepContext;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import com.sunshine.orchestrator.taskboard.TodoTasksBridge;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * AS2 P1：ReAct 执行步的 Middleware（spec 4.1.2，方案 A）。
 *
 * <p>onReasoning 入口 prepare think（有 ThinkingBlock 才落地）/ 出口兜底闭 think + TaskBoard 占位。
 * ThinkingBlockEnd 由 Runtime 路由提前结掉 think（不等 tool_call）。
 * onActing 入口再兜底 completeThink，避免漏 End 事件时空 running 残留。
 *
 * <p>onActing：
 * <ul>
 *   <li>入口：开 tool 步（跳过 manage_tasks / spawn_subagent / request_decision），登记可取消工具</li>
 *   <li>返回 Flux 内 doOnNext 拦截 {@link ToolResultTextDeltaEvent} 按 toolCallId 累积结果文本，
 *       {@link ToolResultEndEvent} 触发收口（摘要/editDiff/取消判定）。
 *       streamEvents 事件流不携带 ToolResultBlock，故以 delta 累积还原结果文本（方案 A）</li>
 *   <li>元工具 request_decision / spawn_subagent / todo_write / think_summary 视为只读批次；
 *       FailureBudget 落地时不计 request_decision</li>
 * </ul>
 *
 * <p>流式 reasoning/content delta 不在此处（由 ReActAgentRuntime 经 streamEvents 路由进 bridge）。
 *
 * <p>P2-1（E5）：middleware 改为无状态——bridgeId 不再为构造参数，由 ReActAgentRuntime
 * 经 RuntimeContext 注入（{@link #CTX_BRIDGE_ID}），使 HarnessAgent 指纹缓存可安全复用
 * 共享 middleware 实例；toolUseById 收窄为 onActing 局部 Map，消除跨 call 污染。
 */
@Slf4j
public class ProcessingStepMiddleware implements MiddlewareBase {

    /** RuntimeContext key：per-call bridgeId（由 runtime 注入） */
    public static final String CTX_BRIDGE_ID = "sunshine.bridgeId";

    /** RuntimeContext key：ReAct 每轮推理迭代计数（per-call，软限额预警用） */
    public static final String CTX_REACT_ITERATION = "sunshine.react.iteration";

    /** RuntimeContext key：实际生效的 maxIters（由 runtime 注入，含 request 覆盖，middleware 读取） */
    public static final String CTX_REACT_MAX_ITERS = "sunshine.react.maxIters";

    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final SandboxWriteEditPlaceholderSupport writeEditPlaceholder;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;
    private final PromptCatalogHolder catalogHolder;

    public ProcessingStepMiddleware(
            ToolCatalogService toolCatalogService,
            AgentExecutionProperties executionProperties,
            TaskBoardTimelineSupport taskBoardTimelineSupport,
            SandboxTimelineLabelService sandboxTimelineLabels,
            SandboxWriteEditPlaceholderSupport writeEditPlaceholder,
            CancellableToolRunRegistry cancellableToolRunRegistry,
            PromptCatalogHolder catalogHolder) {
        this.toolCatalogService = toolCatalogService;
        this.executionProperties = executionProperties;
        this.taskBoardTimelineSupport = taskBoardTimelineSupport;
        this.sandboxTimelineLabels = sandboxTimelineLabels;
        this.writeEditPlaceholder = writeEditPlaceholder;
        this.cancellableToolRunRegistry = cancellableToolRunRegistry;
        this.catalogHolder = catalogHolder;
    }

    /** per-call bridgeId：由 ReActAgentRuntime 经 RuntimeContext 注入（缓存复用下禁止实例字段） */
    private static String bridgeIdOf(RuntimeContext ctx) {
        return ctx != null ? ctx.get(CTX_BRIDGE_ID) : null;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        // 历史消息合法性修复：上一次流被中断（如 llm-gateway 读超时）时，AgentScope 可能把
        // 已累积 tool_calls 但无 tool 响应的 assistant 消息写入历史。OpenAI 兼容 API
        // （DeepSeek）会校验「tool_calls 必须紧跟 tool 响应」，直接 400 拒绝，进而触发熔断、
        // 长期降级到无 reasoning 通道的模型，导致思考内容混入正文。发送前补齐缺失的
        // tool 响应，从根因上避免 400，让主模型持续走 reasoning 通道。
        List<Msg> messages = repairOrphanToolCalls(input.messages());
        // AgentScope maxIters 超限总结轮不传 tools（summaryStream 传 null）。DeepSeek-V4 在
        // 无 tools 参数时若仍想调用工具，会把 DSML 协议块直接写进 content，前端渲染成乱码且
        // 工具调用跑到正文后面。这里检测总结轮并向模型注入「如实汇报进度」的约束，从
        // 根因上阻止 DSML 泄漏（对应 AgentScope 官方 PR #1877 的 tool_choice=none 思路）。
        // 总结轮本质是软中断：任务未必完成，只应如实汇报，禁止为凑结论编造结果；
        // 且平台内部机制（轮次/上限）不得出现在面向用户的内容里。
        if (input.tools() == null || input.tools().isEmpty()) {
            boolean summaryTurn = messages.stream()
                    .anyMatch(m -> containsMaxItersSummary(m));
            if (summaryTurn) {
                String instruction = catalogInstruction("mode-overlay.react-summary-turn");
                if (!StringUtils.hasText(instruction)) {
                    log.warn("[ProcessingStepMiddleware] catalog missing id=mode-overlay.react-summary-turn");
                    return next.apply(new ModelCallInput(messages, input.tools(), input.options(), input.model()));
                }
                List<Msg> guarded = new ArrayList<>(messages.size() + 1);
                guarded.addAll(messages);
                guarded.add(UserMessage.builder()
                        .content(TextBlock.builder().text(instruction).build())
                        .build());
                return next.apply(new ModelCallInput(guarded, input.tools(), input.options(), input.model()));
            }
        }
        return next.apply(new ModelCallInput(messages, input.tools(), input.options(), input.model()));
    }

    /** Catalog 缺失 → 空 + warn（seed 保证正常有值；缺失时该轮不注入约束） */
    private String catalogInstruction(String id) {
        return catalogHolder.snapshot().entry(id)
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElse("");
    }

    /** 历史消息中 assistant 声明了 tool_calls 但无对应 tool 响应（流中断残留）时，补一条占位 tool 响应 */
    private static List<Msg> repairOrphanToolCalls(List<Msg> messages) {
        boolean repaired = false;
        List<Msg> result = new ArrayList<>(messages.size() + 2);
        for (Msg m : messages) {
            result.add(m);
            if (m.getRole() == MsgRole.ASSISTANT) {
                for (ToolUseBlock tu : m.getContentBlocks(ToolUseBlock.class)) {
                    String callId = tu.getId();
                    if (callId == null || callId.isBlank() || hasToolResult(messages, callId)) {
                        continue;
                    }
                    String toolName = tu.getName() != null ? tu.getName() : "unknown";
                    result.add(new ToolResultMessage(callId, toolName,
                            "[工具调用因流中断未产生结果，本次调用视为失败，请继续下一步。]"));
                    repaired = true;
                }
            }
        }
        if (repaired) {
            log.warn("[Middleware] 历史消息存在孤儿 tool_calls，已补占位 tool 响应: messages={} -> {}",
                    messages.size(), result.size());
        }
        return repaired ? result : messages;
    }

    /** 任意消息中是否已存在该 toolCallId 对应的 tool 响应 */
    private static boolean hasToolResult(List<Msg> messages, String callId) {
        for (Msg m : messages) {
            if (m.getRole() == MsgRole.TOOL
                    && m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream()
                            .anyMatch(r -> callId.equals(r.getId()))) {
                return true;
            }
        }
        return false;
    }

    /** 总结轮消息：AgentScope 注入的 maxIters 收尾提示（msg 正文或首文本块含特征串） */
    private static boolean containsMaxItersSummary(Msg m) {
        String text = m.getTextContent();
        if (text != null && text.contains("maximum iterations")) {
            return true;
        }
        return m.getContent().stream()
                .filter(b -> b instanceof TextBlock)
                .anyMatch(b -> ((TextBlock) b).getText() != null
                        && ((TextBlock) b).getText().contains("maximum iterations"));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String bridgeId = bridgeIdOf(ctx);
        ReasoningInput effectiveInput = applySoftLimitWarning(ctx, input);
        StepEventBridge.emit(bridgeId, ProcessingTimelineSession::beginReasoningRound);
        return next.apply(effectiveInput)
                .doFinally(sig -> StepEventBridge.emit(bridgeId, session -> {
                    session.endReasoningRound();
                    if (isTaskBoardEnabled()) {
                        taskBoardTimelineSupport.ensurePlaceholderAfterFirstThink(session);
                    }
                }));
    }

    /**
     * 软限额预警：ReAct 模型感知不到轮数上限，触达 maxIters 时 AgentScope 会强制 summarizing，
     * 在最后一轮工具调用后输出中间态总结并挂起未完成工具（时间线混乱根因）。剩余轮数 ≤ 阈值时
     * 在模型输入末尾注入收束提示，引导模型提前自然终止（toolCalls 为空），从源头避免触达总结轮。
     */
    private ReasoningInput applySoftLimitWarning(RuntimeContext ctx, ReasoningInput input) {
        if (ctx == null || input == null || input.messages() == null || input.messages().isEmpty()) {
            return input;
        }
        int maxIters = resolveMaxIters(ctx);
        int margin = resolveSoftLimitMargin(maxIters);
        if (maxIters <= 0 || margin <= 0) {
            return input;
        }
        int iter = incrementIteration(ctx);
        int remaining = maxIters - iter + 1;
        if (remaining > margin) {
            return input;
        }
        log.info("[Middleware] ReAct soft-limit warning: iter={}/maxIters={} remaining={}",
                iter, maxIters, remaining);
        String instruction = catalogInstruction("mode-overlay.react-soft-limit");
        if (!StringUtils.hasText(instruction)) {
            log.warn("[Middleware] catalog missing id=mode-overlay.react-soft-limit");
            return input;
        }
        List<Msg> messages = new ArrayList<>(input.messages().size() + 1);
        messages.addAll(input.messages());
        messages.add(UserMessage.builder()
                .content(TextBlock.builder().text(instruction).build())
                .build());
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    private int resolveMaxIters(RuntimeContext ctx) {
        Integer injected = ctx != null ? ctx.get(CTX_REACT_MAX_ITERS) : null;
        if (injected != null && injected > 0) {
            return injected;
        }
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getMaxIters() : 0;
    }

    /**
     * 软限额收束缓冲轮数 = maxIters 的 20%（maxIters/5），上限 10 轮：
     * chat=50 → 缓冲 10（约第 40 轮起收束）、task=100 → 缓冲 10（约第 90 轮起）、
     * subagent=30 → 缓冲 6（约第 24 轮起）。固定 margin 在 maxIters 增大后预警过早/过晚，
     * 按比例自动计算适配各档位轮数上限。
     */
    static int resolveSoftLimitMargin(int maxIters) {
        if (maxIters <= 0) {
            return 0;
        }
        return Math.min(Math.max(maxIters / 5, 1), 10);
    }

    private int incrementIteration(RuntimeContext ctx) {
        if (ctx == null) {
            return 0;
        }
        Integer it = ctx.get(CTX_REACT_ITERATION);
        int next = (it == null ? 0 : it) + 1;
        ctx.put(CTX_REACT_ITERATION, next);
        return next;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String bridgeId = bridgeIdOf(ctx);
        // 兜底：ThinkingBlockEnd 未到（或本轮无 Thinking）时，acting 前结掉残留 running think
        StepEventBridge.emit(bridgeId, ProcessingTimelineSession::completeThinkIfRunning);
        List<ToolUseBlock> toolCalls = input.toolCalls();
        // toolCallId -> 累积的结果文本（ToolResultTextDeltaEvent 按 toolCallId 分组还原 ToolResultBlock 文本）
        Map<String, StringBuilder> resultTextById = new ConcurrentHashMap<>();
        // toolCallId -> 本次 onActing 的 ToolUseBlock（局部缓存，End 事件回查 input 用，随 call 生命周期回收）
        Map<String, ToolUseBlock> toolUseById = new ConcurrentHashMap<>();
        // 入口：开 tool 步（跳过 think_summary / spawn_subagent / request_decision / todo_write，不上 tool-* 步）
        for (ToolUseBlock tu : toolCalls) {
            String id = tu.getId();
            if (id != null) {
                toolUseById.put(id, tu);
            }
            String toolName = tu.getName();
            if (ThinkSummaryTool.NAME.equals(toolName)) {
                applyThinkSummary(bridgeId, tu);
                continue;
            }
            if (RequestDecisionTool.NAME.equals(toolName)
                    || SpawnSubagentTool.NAME.equals(toolName)
                    || TodoTasksBridge.isTodoWrite(toolName)) {
                // 元工具不上 tool-* 步，但仍须绑定 toolUse→bridge，供 AgentTool 多会话定位 messageId
                StepEventBridge.bindToolUseBridge(tu.getId(), bridgeId);
                continue;
            }
            beginToolStep(bridgeId, tu);
        }
        return executeByReadWritePartition(toolCalls, next)
                .doOnNext(ev -> {
                    if (ev instanceof ToolResultTextDeltaEvent d) {
                        resultTextById.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder())
                                .append(d.getDelta());
                    } else if (ev instanceof ToolResultEndEvent end) {
                        completeToolStep(agent, ctx, bridgeId, end, resultTextById, toolUseById);
                    }
                })
                .doFinally(sig -> {
                    // 兜底：异常/取消未收到 End 事件的 tool 步不残留 bridge 绑定
                    for (ToolUseBlock tu : toolCalls) {
                        String id = tu.getId();
                        if (id != null) {
                            StepEventBridge.unbindToolUseBridge(id);
                        }
                    }
                });
    }

    /**
     * 同轮多 tool_calls 读写分区调度：连续只读工具一批（框架并行），写工具单独串行。
     * 避免并发写操作导致的状态竞争（如两个写工具同时改同一资源）。
     * 单工具或全读时直接整批执行，无额外开销。
     */
    private void applyThinkSummary(String bridgeId, ToolUseBlock tu) {
        Object raw = tu.getInput() != null ? tu.getInput().get("summary") : null;
        log.info("[ThinkSummary] applyThinkSummary bridgeId={} summary={}", bridgeId, raw);
        if (raw instanceof String s && StringUtils.hasText(s)) {
            StepEventBridge.emit(bridgeId, session -> session.applyThinkStepSummary(s));
        }
    }
    private Flux<AgentEvent> executeByReadWritePartition(
            List<ToolUseBlock> toolCalls,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (toolCalls.size() <= 1) {
            return next.apply(new ActingInput(toolCalls));
        }
        List<List<ToolUseBlock>> batches = partitionByReadWrite(toolCalls);
        if (batches.size() == 1) {
            return next.apply(new ActingInput(toolCalls));
        }
        List<Flux<AgentEvent>> batchFluxes = batches.stream()
                .map(batch -> next.apply(new ActingInput(batch)))
                .toList();
        return Flux.concat(batchFluxes);
    }

    /**
     * 按 sideEffect 将 toolCalls 切成连续批次：连续只读工具归一批，写工具单独成批。
     * 元工具（request_decision / spawn_subagent / await_tool_run / todo_write / think_summary）视为只读（不竞争外部状态）。
     */
    private List<List<ToolUseBlock>> partitionByReadWrite(List<ToolUseBlock> toolCalls) {
        List<List<ToolUseBlock>> batches = new ArrayList<>();
        List<ToolUseBlock> currentReadOnlyBatch = new ArrayList<>();
        for (ToolUseBlock tu : toolCalls) {
            if (isWriteTool(tu.getName())) {
                if (!currentReadOnlyBatch.isEmpty()) {
                    batches.add(currentReadOnlyBatch);
                    currentReadOnlyBatch = new ArrayList<>();
                }
                batches.add(List.of(tu));
            } else {
                currentReadOnlyBatch.add(tu);
            }
        }
        if (!currentReadOnlyBatch.isEmpty()) {
            batches.add(currentReadOnlyBatch);
        }
        return batches;
    }

    /** 写工具判定：catalog sideEffect=write 或沙箱写文件工具（sandbox__write/edit）；exec 不加锁避免长任务阻塞会话 */
    private boolean isWriteTool(String toolName) {
        if (AwaitToolRunTool.NAME.equals(toolName)) {
            return false;
        }
        if (SandboxIds.WRITE.equals(toolName) || SandboxIds.EDIT.equals(toolName)) {
            return true;
        }
        return toolCatalogService.find(toolName)
                .map(e -> "write".equals(e.sideEffect()))
                .orElse(false);
    }

    /** 入口开 tool 步（对齐 PreActing）：开步 + sandbox active 文案 + 取消登记。
     * write/edit 可能已在 ToolCallStart/Delta 占位开步，此处复用避免双卡。 */
    private void beginToolStep(String bridgeId, ToolUseBlock toolUse) {
        String toolName = toolUse.getName();
        String toolUseId = toolUse.getId();
        StepEventBridge.bindToolUseBridge(toolUseId, bridgeId);
        Map<String, Object> toolInput = toolInput(toolUse);
        String sandboxActive = sandboxActiveSummary(toolName, toolInput);
        boolean backgroundExec = SandboxIds.EXEC.equals(toolName)
                && Boolean.TRUE.equals(asBackgroundFlag(toolInput.get("background")));
        boolean awaitTool = AwaitToolRunTool.NAME.equals(toolName);
        boolean cancellable = cancellableToolRunRegistry.isCancellableTool(toolName)
                && StringUtils.hasText(toolUseId);
        String existingStepId = StepEventBridge.stepIdForToolUse(toolUseId);
        if (existingStepId != null) {
            writeEditPlaceholder.clearBuffers(toolUseId);
            final String stepId = existingStepId;
            StepEventBridge.emit(bridgeId, session -> {
                if (sandboxActive != null) {
                    session.progress(stepId, sandboxActive);
                }
                if (cancellable) {
                    // 占位步可能未标 cancellable；执行入口补齐
                    session.markCurrentToolCancellable();
                }
            });
            if (cancellable) {
                registerCancellable(bridgeId, toolName, toolUseId, toolInput);
            }
            return;
        }
        String baseStepId = toolCatalogService.timelineStepId(toolName);
        String phase = toolCatalogService.timelinePhase(toolName);
        final String[] stepHolder = new String[1];
        StepEventBridge.emit(bridgeId, session -> {
            session.noteToolCallPending();
            stepHolder[0] = session.beginToolStep(baseStepId, phase);
            if (stepHolder[0] != null) {
                if (awaitTool) {
                    session.bindStepDisplayName(stepHolder[0], AwaitToolRunLabels.label());
                    session.progressCurrentToolStep(AwaitToolRunLabels.active());
                } else if (backgroundExec) {
                    session.bindStepDisplayName(stepHolder[0], AwaitToolRunLabels.backgroundExecLabel());
                }
            }
            if (sandboxActive != null) {
                session.progressCurrentToolStep(sandboxActive);
            }
            if (cancellable) {
                session.markCurrentToolCancellable();
            }
        });
        if (stepHolder[0] != null) {
            StepEventBridge.bindToolUseStep(toolUseId, stepHolder[0]);
        }
        // 出卡即登记：UI 可立即 cancel（messageId 必须为 assistant，禁止 bridgeId 冒充）
        if (cancellable) {
            registerCancellable(bridgeId, toolName, toolUseId, toolInput);
        }
    }

    /** 工具步收口（方案 A）：ToolResultEndEvent 触发，用累积的结果文本完成摘要/editDiff/取消判定 */
    private void completeToolStep(
            Agent agent, RuntimeContext ctx, String bridgeId,
            ToolResultEndEvent end, Map<String, StringBuilder> resultTextById,
            Map<String, ToolUseBlock> toolUseById) {
        String toolName = end.getToolCallName();
        String toolUseId = end.getToolCallId();
        if (toolName == null) {
            return;
        }
        // think_summary 摘要工具：入口已 applyThinkSummary，结果无需处理 tool 步
        if (ThinkSummaryTool.NAME.equals(toolName)) {
            StepEventBridge.unbindToolUseBridge(toolUseId);
            return;
        }
        // 原生 todo_write 是状态工具（无结果可分析）：不上 tool-* 步、不 recordToolCompleted，
        // 避免触发「已完成任务板的工具结果综合分析」单独 think 步；任务列表投影由
        // TodoTasksBridge 完成，且 TaskBoardTimelineSupport 已自带 think 锚定防连续复用覆盖。
        if (TodoTasksBridge.isTodoWrite(toolName)) {
            TodoTasksBridge.emitTodoTasks(agent, ctx, bridgeId, taskBoardTimelineSupport);
            StepEventBridge.unbindToolUseBridge(toolUseId);
            return;
        }
        // spawn_subagent / request_decision 不上 tool-* 步，但须 recordToolCompleted，否则后续推理合并进首个 think
        if (SpawnSubagentTool.NAME.equals(toolName)) {
            StepEventBridge.emit(bridgeId, session ->
                    session.recordToolCompleted(SpawnSubagentLabels.label()));
            StepEventBridge.unbindToolUseBridge(toolUseId);
            return;
        }
        if (RequestDecisionTool.NAME.equals(toolName)) {
            StepEventBridge.emit(bridgeId, session ->
                    session.recordToolCompleted(DecisionLabels.label()));
            StepEventBridge.unbindToolUseBridge(toolUseId);
            return;
        }
        StringBuilder acc = resultTextById.remove(toolUseId);
        String rawText = acc != null ? acc.toString() : "";
        ToolUseBlock toolUse = toolUseById.get(toolUseId);
        final String summaryLine;
        final String expandDetail;
        final StepMetadata meta;
        if (toolCatalogService.isRagTool(toolName)) {
            // search_knowledge 无 catalog 摘要模板；metadata/after 须从工具原始命中正文解析
            summaryLine = rawText;
            expandDetail = ToolExpandDetailSupport.resolveExpandDetail(null, rawText);
            meta = null;
        } else if (sandboxTimelineLabels.isSandboxTool(toolName)) {
            Map<String, Object> input = toolInput(toolUse);
            String raw = rawText != null ? rawText.strip() : "";
            // 用户单工具取消：Controller 已 pause 终态，禁止再 complete 覆盖
            if (cancellableToolRunRegistry.consumeRecentlyCancelled(toolUseId)) {
                StepEventBridge.emit(bridgeId, session -> {
                    session.recordToolCompleted(toolCatalogService.displayName(toolName));
                    session.noteToolCallDone();
                });
                StepEventBridge.unbindToolUseBridge(toolUseId);
                return;
            }
            Map<String, Object> enriched = SandboxStepContext.enrichInput(toolName, input, raw);
            // 读文件：主行附行范围（L{start}-{end}），前端据此定位文件起始行
            if (SandboxIds.READ.equals(toolName)) {
                summaryLine = sandboxTimelineLabels.readAfter(
                        toolCatalogService.displayName(toolName), enriched, raw);
            } else {
                summaryLine = sandboxTimelineLabels.after(toolName, toolCatalogService.displayName(toolName), enriched);
            }
            StepMetadata sandboxMeta = SandboxStepContext.metadata(toolName, enriched, summaryLine);
            if (SandboxIds.WRITE.equals(toolName) || SandboxIds.EDIT.equals(toolName)) {
                if (SandboxIds.EDIT.equals(toolName)) {
                    SandboxEditDiff editDiff = SandboxEditDiffHolder.take(toolUseId);
                    if (editDiff != null) {
                        sandboxMeta = StepMetadata.withEditDiff(sandboxMeta, editDiff);
                        expandDetail = editDiff.toUnifiedText();
                    } else {
                        expandDetail = null;
                    }
                } else {
                    String bodyExpand = HitlParamSupport.expandBodyFromParams(toStringParams(input));
                    expandDetail = StringUtils.hasText(bodyExpand)
                            ? bodyExpand
                            : (!raw.isEmpty() ? raw : null);
                }
            } else {
                expandDetail = !raw.isEmpty() ? raw : null;
            }
            meta = sandboxMeta;
        } else if (AwaitToolRunTool.NAME.equals(toolName)) {
            // 文案 SSOT = Catalog timeline.steps.await-tool；勿把工具 id / 原始 JSON 当主行
            summaryLine = AwaitToolRunLabels.after();
            expandDetail = ToolExpandDetailSupport.resolveExpandDetail(null, rawText);
            meta = null;
        } else {
            summaryLine = toolCatalogService.timelineSummary(toolName, rawText);
            expandDetail = ToolExpandDetailSupport.resolveExpandDetail(summaryLine, rawText);
            meta = null;
        }
        final StepMetadata finalMeta = meta;
        final String completedDisplayName = AwaitToolRunTool.NAME.equals(toolName)
                ? AwaitToolRunLabels.label()
                : toolCatalogService.displayName(toolName);
        StepEventBridge.emit(bridgeId, session -> {
            if (finalMeta != null) {
                session.completeToolStepForToolUse(toolUseId, summaryLine, expandDetail, finalMeta);
            } else {
                session.completeToolStepForToolUse(toolUseId, summaryLine, expandDetail);
            }
            session.recordToolCompleted(completedDisplayName);
            session.noteToolCallDone();
        });
        StepEventBridge.unbindToolUseBridge(toolUseId);
    }

    private void registerCancellable(String bridgeId, String toolName, String toolUseId, Map<String, Object> input) {
        String messageId = StepEventBridge.hitlAssistantMessageId(bridgeId);
        if (!StringUtils.hasText(messageId)) {
            messageId = StepEventBridge.activeMessageId();
        }
        if (StringUtils.hasText(messageId)) {
            String expandDetail = SandboxCancelExpand.detail(toolName, input);
            cancellableToolRunRegistry.register(toolUseId, messageId, toolName, null, toolUseId, expandDetail);
        }
    }

    private String sandboxActiveSummary(String toolName, Map<String, Object> input) {
        if (!sandboxTimelineLabels.isSandboxTool(toolName)) {
            return null;
        }
        return sandboxTimelineLabels.active(toolName, toolCatalogService.displayName(toolName), input);
    }

    private static Map<String, Object> toolInput(ToolUseBlock toolUse) {
        if (toolUse == null || toolUse.getInput() == null) {
            return Map.of();
        }
        return toolUse.getInput();
    }

    private static Map<String, String> toStringParams(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            out.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    private static Boolean asBackgroundFlag(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Boolean.parseBoolean(s.strip());
        }
        return null;
    }

    private boolean isTaskBoardEnabled() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
    }
}
