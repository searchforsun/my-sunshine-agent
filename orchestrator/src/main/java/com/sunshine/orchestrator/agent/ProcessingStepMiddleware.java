package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.hitl.HitlParamSupport;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.ToolExpandDetailSupport;
import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxCancelExpand;
import com.sunshine.orchestrator.sandbox.SandboxEditDiffHolder;
import com.sunshine.orchestrator.sandbox.SandboxIds;
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
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ToolUseBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * AS2 P1：ProcessingStepHook 的 Middleware 替代（spec 4.1.2，方案 A）。
 *
 * <p>onReasoning 入口开 think / 出口闭 think + TaskBoard 占位（行为对齐 PreReasoning/PostReasoning）。
 *
 * <p>onActing：
 * <ul>
 *   <li>入口：开 tool 步（跳过 manage_tasks / spawn_subagent），登记可取消工具（对齐 PreActing）</li>
 *   <li>返回 Flux 内 doOnNext 拦截 {@link ToolResultTextDeltaEvent} 按 toolCallId 累积结果文本，
 *       {@link ToolResultEndEvent} 触发完整 PostActing 收口（摘要/editDiff/取消判定）。
 *       streamEvents 事件流不携带 ToolResultBlock，故以 delta 累积还原结果文本（方案 A）</li>
 * </ul>
 *
 * <p>流式 reasoning/content delta 不在此处（由 EventMapper 经 streamEvents 驱动）。
 *
 * <p>P2-1（E5）：middleware 改为无状态——bridgeId 不再为构造参数，由 ReActAgentRuntime
 * 经 RuntimeContext 注入（{@link #CTX_BRIDGE_ID}），使 HarnessAgent 指纹缓存可安全复用
 * 共享 middleware 实例；toolUseById 收窄为 onActing 局部 Map，消除跨 call 污染。
 */
@Slf4j
public class ProcessingStepMiddleware implements MiddlewareBase {

    /** RuntimeContext key：per-call bridgeId（由 runtime 注入） */
    public static final String CTX_BRIDGE_ID = "sunshine.bridgeId";

    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;

    public ProcessingStepMiddleware(
            ToolCatalogService toolCatalogService,
            AgentExecutionProperties executionProperties,
            TaskBoardTimelineSupport taskBoardTimelineSupport,
            SandboxTimelineLabelService sandboxTimelineLabels,
            CancellableToolRunRegistry cancellableToolRunRegistry) {
        this.toolCatalogService = toolCatalogService;
        this.executionProperties = executionProperties;
        this.taskBoardTimelineSupport = taskBoardTimelineSupport;
        this.sandboxTimelineLabels = sandboxTimelineLabels;
        this.cancellableToolRunRegistry = cancellableToolRunRegistry;
    }

    /** per-call bridgeId：由 ReActAgentRuntime 经 RuntimeContext 注入（缓存复用下禁止实例字段） */
    private static String bridgeIdOf(RuntimeContext ctx) {
        return ctx != null ? ctx.get(CTX_BRIDGE_ID) : null;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String bridgeId = bridgeIdOf(ctx);
        StepEventBridge.emit(bridgeId, ProcessingTimelineSession::beginReasoningRound);
        return next.apply(input)
                .doFinally(sig -> StepEventBridge.emit(bridgeId, session -> {
                    session.endReasoningRound();
                    if (isTaskBoardEnabled()) {
                        taskBoardTimelineSupport.ensurePlaceholderAfterFirstThink(session);
                    }
                }));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String bridgeId = bridgeIdOf(ctx);
        List<ToolUseBlock> toolCalls = input.toolCalls();
        // toolCallId -> 累积的结果文本（ToolResultTextDeltaEvent 按 toolCallId 分组还原 ToolResultBlock 文本）
        Map<String, StringBuilder> resultTextById = new ConcurrentHashMap<>();
        // toolCallId -> 本次 onActing 的 ToolUseBlock（局部缓存，End 事件回查 input 用，随 call 生命周期回收）
        Map<String, ToolUseBlock> toolUseById = new ConcurrentHashMap<>();
        // 入口：开 tool 步（跳过 manage_tasks / spawn_subagent，二者不上 tool-* 步）
        for (ToolUseBlock tu : toolCalls) {
            String id = tu.getId();
            if (id != null) {
                toolUseById.put(id, tu);
            }
            String toolName = tu.getName();
            if (SpawnSubagentTool.NAME.equals(toolName) || TodoTasksBridge.isTodoWrite(toolName)) {
                continue;
            }
            beginToolStep(bridgeId, tu);
        }
        return next.apply(input)
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

    /** 入口开 tool 步（对齐 PreActing）：开步 + sandbox active 文案 + 取消登记 */
    private void beginToolStep(String bridgeId, ToolUseBlock toolUse) {
        String toolName = toolUse.getName();
        String toolUseId = toolUse.getId();
        StepEventBridge.bindToolUseBridge(toolUseId, bridgeId);
        String baseStepId = toolCatalogService.timelineStepId(toolName);
        String phase = toolCatalogService.timelinePhase(toolName);
        Map<String, Object> toolInput = toolInput(toolUse);
        String sandboxActive = sandboxActiveSummary(toolName, toolInput);
        boolean cancellable = cancellableToolRunRegistry.isCancellableTool(toolName)
                && StringUtils.hasText(toolUseId);
        final String[] stepHolder = new String[1];
        StepEventBridge.emit(bridgeId, session -> {
            session.noteToolCallPending();
            stepHolder[0] = session.beginToolStep(baseStepId, phase);
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

    /** PostActing 收口（方案 A）：ToolResultEndEvent 触发，用累积的结果文本复现 legacy PostActing 全套逻辑 */
    private void completeToolStep(
            Agent agent, RuntimeContext ctx, String bridgeId,
            ToolResultEndEvent end, Map<String, StringBuilder> resultTextById,
            Map<String, ToolUseBlock> toolUseById) {
        String toolName = end.getToolCallName();
        String toolUseId = end.getToolCallId();
        if (toolName == null) {
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
        // spawn_subagent 不上 tool-* 步，但须 recordToolCompleted，否则后续推理合并进首个 think
        if (SpawnSubagentTool.NAME.equals(toolName)) {
            StepEventBridge.emit(bridgeId, session ->
                    session.recordToolCompleted(SpawnSubagentLabels.label()));
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
            summaryLine = sandboxTimelineLabels.after(toolName, toolCatalogService.displayName(toolName), enriched);
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
        } else {
            summaryLine = toolCatalogService.timelineSummary(toolName, rawText);
            expandDetail = ToolExpandDetailSupport.resolveExpandDetail(summaryLine, rawText);
            meta = null;
        }
        final StepMetadata finalMeta = meta;
        StepEventBridge.emit(bridgeId, session -> {
            if (finalMeta != null) {
                session.completeToolStepForToolUse(toolUseId, summaryLine, expandDetail, finalMeta);
            } else {
                session.completeToolStepForToolUse(toolUseId, summaryLine, expandDetail);
            }
            session.recordToolCompleted(toolCatalogService.displayName(toolName));
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

    private boolean isTaskBoardEnabled() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
    }
}
