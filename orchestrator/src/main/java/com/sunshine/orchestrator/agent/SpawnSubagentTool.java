package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.AgentCatalogEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.ResourceKindFilter;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.processing.ContentSegmentCoordinator;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** ReAct 元工具 — 主 Agent 按需创建隔离子 Agent，不占 tool-manager Catalog */
@Slf4j
@Component
public class SpawnSubagentTool implements AgentTool {

    public static final String NAME = "spawn_subagent";
    /** Catalog id：mode-overlay.subagent */
    public static final String OVERLAY_CATALOG_ID = "mode-overlay.subagent";

    private final AgentExecutionProperties executionProperties;
    private final SpawnSubagentTimelineSupport timelineSupport;
    private final ToolSetResolver toolSetResolver;
    private final PromptCatalogHolder catalogHolder;
    private final SpawnRunRegistry spawnRunRegistry;
    private final AgentCatalogService agentCatalogService;
    private final AgentExecutorRouter agentExecutorRouter;
    private final AsyncToolRunRegistry asyncToolRunRegistry;
    private final SkillCatalogService skillCatalogService;

    public SpawnSubagentTool(
            AgentExecutionProperties executionProperties,
            SpawnSubagentTimelineSupport timelineSupport,
            ToolSetResolver toolSetResolver,
            PromptCatalogHolder catalogHolder,
            SpawnRunRegistry spawnRunRegistry,
            AgentCatalogService agentCatalogService,
            AgentExecutorRouter agentExecutorRouter,
            AsyncToolRunRegistry asyncToolRunRegistry,
            SkillCatalogService skillCatalogService) {
        this.executionProperties = executionProperties;
        this.timelineSupport = timelineSupport;
        this.toolSetResolver = toolSetResolver;
        this.catalogHolder = catalogHolder;
        this.spawnRunRegistry = spawnRunRegistry;
        this.agentCatalogService = agentCatalogService;
        this.agentExecutorRouter = agentExecutorRouter;
        this.asyncToolRunRegistry = asyncToolRunRegistry;
        this.skillCatalogService = skillCatalogService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "创建隔离子 Agent：可指定预定义智能体 agentId（使用该智能体的系统提示词/工具/配置），"
                + "或仅传 prompt 创建临时子 Agent；返回子任务最终文本。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("prompt", Map.of("type", "string", "description", "给子 Agent 的完整任务说明（必填）"));
        props.put("agent_id", Map.of(
                "type", "string",
                "description", "预定义智能体 ID（可选，如 policy-agent / finance-agent）"));
        props.put("label", Map.of("type", "string", "description", "时间线卡片短标题（可选）"));
        props.put("background", Map.of(
                "type", "boolean",
                "description", "可选；true=立即返回 runId，主 Agent 用 await_tool_run 收终稿；默认 false"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("prompt"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                    Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                    String prompt = stringParam(input, "prompt");
                    String agentId = stringParam(input, "agent_id");
                    String label = stringParam(input, "label");
                    Boolean background = asBoolean(input.get("background"));
                    String text = spawnSubagent(prompt, agentId, label, toolUseId, background);
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    /** 单测入口：无 toolUseId 时回退 activeMessageId（单会话）。 */
    String spawnSubagent(String prompt, String agentId, String label) {
        return spawnSubagent(prompt, agentId, label, null, null);
    }

    String spawnSubagent(String prompt, String agentId, String label, String toolUseId) {
        return spawnSubagent(prompt, agentId, label, toolUseId, null);
    }

    String spawnSubagent(String prompt, String agentId, String label, String toolUseId, Boolean background) {
        AgentExecutionProperties.React.Subagent subCfg = subagentConfig();
        if (subCfg == null || !subCfg.isEnabled()) {
            return errorJson("spawn_subagent 未启用");
        }
        if (!StringUtils.hasText(prompt)) {
            return errorJson("prompt 不能为空");
        }
        String messageId = StepEventBridge.resolveMessageIdForToolUse(toolUseId);
        if (!StringUtils.hasText(messageId)) {
            return errorJson("无法定位当前会话消息");
        }
        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(messageId);
        if (audit == null
                || !StringUtils.hasText(audit.userId())
                || !StringUtils.hasText(audit.tenantId())
                || !StringUtils.hasText(audit.conversationId())) {
            return errorJson("缺少会话审计上下文（userId/tenantId/conversationId）");
        }
        String mainBridge = StepEventBridge.activeMainBridge(messageId);
        if (!StringUtils.hasText(mainBridge)) {
            return errorJson("spawn_subagent 仅可从主 Agent 调用");
        }
        String activeBridge = StepEventBridge.bridgeIdForToolUse(toolUseId);
        if (!StringUtils.hasText(activeBridge)) {
            activeBridge = StepEventBridge.activeBridgeId();
        }
        if (StringUtils.hasText(activeBridge) && activeBridge.startsWith("sub-")) {
            return errorJson("禁止嵌套委派：子 Agent 不可再调用 spawn_subagent");
        }

        String promptText = prompt.strip();
        String displayLabel = StringUtils.hasText(label) ? label.strip() : SpawnSubagentLabels.label();
        int maxIters = subCfg.getMaxIters() > 0 ? subCfg.getMaxIters() : 8;
        long timeoutMs = subCfg.getTimeoutMs() > 0 ? subCfg.getTimeoutMs() : 180_000L;
        List<String> sameToolsAsMain = toolSetResolver.resolveDefaultTools(
                audit.tenantId(), audit.conversationKind());
        String systemOverlay = resolveSubagentOverlay();

        AgentCatalogEntry agentEntry = null;
        if (StringUtils.hasText(agentId)) {
            agentEntry = agentCatalogService.find(agentId.strip()).orElse(null);
            if (agentEntry == null) {
                return errorJson("未找到智能体: " + agentId);
            }
            if (!canAccess(agentEntry, audit.tenantId())) {
                return errorJson("无权访问智能体: " + agentEntry.displayName());
            }
            if (!ResourceKindFilter.matches(agentEntry.kind(), audit.conversationKind())) {
                return errorJson("智能体「" + agentEntry.displayName() + "」不适用于当前会话形态");
            }
        }
        List<String> toolIds;
        String resolvedSystemOverlay;
        String resolvedSkillId;
        String resolvedPermissionsJson;
        String resolvedModelConfigJson;
        int resolvedMaxIters;
        if (agentEntry != null) {
            toolIds = mergeAgentSkillTools(agentEntry);
            resolvedSystemOverlay = agentEntry.systemPrompt();
            // 子 agent 的 skills 经 skillId 加载（PromptComposer.resolveSkillOverlay）；skillIds 不是注入块
            resolvedSkillId = agentEntry.primarySkillId();
            resolvedPermissionsJson = agentEntry.permissionsJson();
            resolvedModelConfigJson = agentEntry.modelConfigJson();
            resolvedMaxIters = agentEntry.maxIters() > 0 ? agentEntry.maxIters() : maxIters;
            if (displayLabel.equals(SpawnSubagentLabels.label())) {
                displayLabel = agentEntry.displayName();
            }
        } else {
            toolIds = sameToolsAsMain;
            resolvedSystemOverlay = systemOverlay;
            resolvedSkillId = null;
            resolvedPermissionsJson = "{}";
            resolvedModelConfigJson = "{}";
            resolvedMaxIters = maxIters;
        }

        AgentRunRequest request = AgentRunRequest.sub(
                AssembledContext.forSubAgent(),
                promptText,
                List.of(),
                audit.userId(),
                audit.tenantId(),
                messageId,
                resolvedSkillId,
                toolIds,
                resolvedSystemOverlay,
                resolvedMaxIters,
                audit.conversationId(),
                agentEntry != null ? agentEntry.kbScope() : null,
                agentEntry != null ? agentEntry.dataScopeJson() : null,
                resolvedPermissionsJson,
                resolvedModelConfigJson)
                .withConversationKind(audit.conversationKind());
        String runId = request.runId();
        String subBridgeId = request.resolveBridgeId();
        SpawnSubagentTimelineBridge subTimeline =
                new SpawnSubagentTimelineBridge(runId, displayLabel, promptText);

        timelineSupport.begin(mainBridge, runId, displayLabel, promptText);
        spawnRunRegistry.register(runId, messageId, promptText, mainBridge, subTimeline);
        // PASS_THROUGH：wrapper 只 fold；原 token 入队供 Flux（禁止 Flux 再 fold，否则 reasoning 翻倍）
        StepEventBridge.bindTokenWrapper(subBridgeId, token -> {
            foldStepToken(mainBridge, subTimeline, token);
            return List.of();
        }, TokenWrapperMode.PASS_THROUGH);
        StepEventBridge.bindHitlBridge(subBridgeId, messageId, true);

        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean runInBackground = Boolean.TRUE.equals(background) && asyncToolEnabled();
        if (runInBackground) {
            return startBackgroundSpawn(
                    agentEntry, request, promptText, runId, messageId, audit.conversationId(),
                    mainBridge, subTimeline, answer, failure, timeoutMs);
        }
        try {
            agentExecutorRouter.dispatch(agentEntry, request, promptText, List.of())
                    .doOnNext(token -> appendAnswerContent(answer, token))
                    .doOnError(failure::set)
                    .blockLast(Duration.ofMillis(timeoutMs));
            if (spawnRunRegistry.isCancelled(runId)) {
                return cancelAndReturn(runId, subTimeline, promptText);
            }
            if (failure.get() != null) {
                if (isInterrupted(failure.get())) {
                    return cancelAndReturn(runId, subTimeline, promptText);
                }
                return failAndReturn(mainBridge, subTimeline, failure.get());
            }
            String result = answer.toString();
            timelineSupport.complete(mainBridge, subTimeline, result);
            if (!StringUtils.hasText(result)) {
                log.warn("[SpawnSubagentTool] 子 Agent 未产出正文 content runId={}", runId);
            }
            return result;
        } catch (Exception e) {
            if (spawnRunRegistry.isCancelled(runId) || isInterrupted(e)) {
                return cancelAndReturn(runId, subTimeline, promptText);
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isTimeout(cause) || isTimeout(e)) {
                String msg = "子 Agent 执行超时（" + timeoutMs + "ms）";
                timelineSupport.fail(mainBridge, subTimeline, msg);
                return msg;
            }
            return failAndReturn(mainBridge, subTimeline, cause != null ? cause : e);
        } finally {
            spawnRunRegistry.unregister(runId);
        }
    }

    /**
     * background=true：立即返回 runId；子卡仍流式；SpawnRunRegistry 延后到终态 unregister。
     */
    private String startBackgroundSpawn(
            AgentCatalogEntry agentEntry,
            AgentRunRequest request,
            String promptText,
            String runId,
            String messageId,
            String conversationId,
            String mainBridge,
            SpawnSubagentTimelineBridge subTimeline,
            StringBuilder answer,
            AtomicReference<Throwable> failure,
            long timeoutMs) {
        if (!asyncToolRunRegistry.tryAcquireSlot(messageId)) {
            spawnRunRegistry.unregister(runId);
            timelineSupport.fail(mainBridge, subTimeline, "本消息后台工具并发已达上限");
            return errorJson("本消息后台工具并发已达上限");
        }
        asyncToolRunRegistry.registerWithId(
                runId,
                AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT,
                messageId,
                conversationId,
                timeoutMs,
                () -> spawnRunRegistry.cancel(runId));
        // 用户取消子卡 / 墙钟 onCancel→cancel → complete + 释放 SpawnRunRegistry（Flux 可能仍挂起）
        spawnRunRegistry.bindOnUserCancel(runId, () -> {
            asyncToolRunRegistry.complete(
                    runId,
                    AsyncToolRunRegistry.Status.CANCELLED,
                    spawnRunRegistry.formatCancelResult(promptText));
            spawnRunRegistry.unregister(runId);
        });
        agentExecutorRouter.dispatch(agentEntry, request, promptText, List.of())
                .doOnNext(token -> {
                    appendAnswerContent(answer, token);
                    asyncToolRunRegistry.updatePartial(runId, answer.toString());
                })
                .doOnError(failure::set)
                .subscribeOn(VirtualThreadExecutors.scheduler())
                .subscribe(
                        token -> { },
                        err -> finishBackgroundSpawn(
                                runId, promptText, mainBridge, subTimeline, answer, failure, timeoutMs, err),
                        () -> finishBackgroundSpawn(
                                runId, promptText, mainBridge, subTimeline, answer, failure, timeoutMs, null));
        return "{\"ok\":true,\"runId\":\"" + escape(runId) + "\",\"status\":\"running\"}";
    }

    private void finishBackgroundSpawn(
            String runId,
            String promptText,
            String mainBridge,
            SpawnSubagentTimelineBridge subTimeline,
            StringBuilder answer,
            AtomicReference<Throwable> failure,
            long timeoutMs,
            Throwable subscribeError) {
        try {
            // 墙钟/用户取消已终态：禁止再写 success/fail 时间线
            if (isAsyncAlreadyTerminal(runId)) {
                return;
            }
            Throwable err = subscribeError != null ? subscribeError : failure.get();
            if (spawnRunRegistry.isCancelled(runId) || isInterrupted(err)) {
                String result = spawnRunRegistry.formatCancelResult(promptText);
                if (subTimeline != null && !subTimeline.userCancelled()) {
                    spawnRunRegistry.flushCancelTerminal(runId, subTimeline, result);
                }
                asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.CANCELLED, result);
                return;
            }
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (isTimeout(cause) || isTimeout(err)) {
                    String msg = "子 Agent 执行超时（" + timeoutMs + "ms）";
                    // 先 claim WALL_TIMEOUT；胜者由 onCancel→spawn.cancel 写取消时间线，勿再 fail
                    asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.WALL_TIMEOUT, msg);
                    return;
                }
                String msg = errMessage(cause != null ? cause : err);
                asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.ERROR, msg);
                // 仅 ERROR 胜者写 fail 时间线
                if (isAsyncStatus(runId, AsyncToolRunRegistry.Status.ERROR)) {
                    timelineSupport.fail(mainBridge, subTimeline, msg);
                }
                return;
            }
            if (spawnRunRegistry.isCancelled(runId) || isAsyncAlreadyTerminal(runId)) {
                return;
            }
            String result = answer.toString();
            // 先 claim DONE；仅胜者写 success 时间线，避免与 WALL_TIMEOUT/CANCELLED 竞态双写
            asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.DONE, result);
            if (!isAsyncStatus(runId, AsyncToolRunRegistry.Status.DONE)) {
                return;
            }
            timelineSupport.complete(mainBridge, subTimeline, result);
            if (!StringUtils.hasText(result)) {
                log.warn("[SpawnSubagentTool] 子 Agent 未产出正文 content runId={}", runId);
            }
        } finally {
            spawnRunRegistry.unregister(runId);
        }
    }

    private boolean isAsyncAlreadyTerminal(String runId) {
        AsyncToolRunRegistry.Snapshot snap = asyncToolRunRegistry.peek(runId);
        return snap != null && snap.status() != AsyncToolRunRegistry.Status.RUNNING;
    }

    private boolean isAsyncStatus(String runId, AsyncToolRunRegistry.Status expected) {
        AsyncToolRunRegistry.Snapshot snap = asyncToolRunRegistry.peek(runId);
        return snap != null && snap.status() == expected;
    }

    private static String errMessage(Throwable error) {
        if (error != null && StringUtils.hasText(error.getMessage())) {
            return error.getMessage().strip();
        }
        return "子 Agent 执行失败";
    }

    private boolean asyncToolEnabled() {
        AgentExecutionProperties.React react = executionProperties != null ? executionProperties.getReact() : null;
        AgentExecutionProperties.React.AsyncTool cfg = react != null ? react.getAsyncTool() : null;
        return cfg == null || cfg.isEnabled();
    }

    /**
     * 收集正文：与 {@link ContentSegmentCoordinator} 相同单调规则。
     * AGENT_RESULT 全量复读不得再 append（否则 complete 终稿翻倍，前端 longerText 会吃更长串）。
     */
    static void appendAnswerContent(StringBuilder answer, StreamToken token) {
        if (answer == null || token == null || token.text() == null || token.text().isEmpty()) {
            return;
        }
        if (!token.isContent() && !(token.isStepDelta() && "result".equals(token.channel()))) {
            return;
        }
        String incoming = token.text();
        String baseline = answer.toString();
        String delta = ContentSegmentCoordinator.resolveDelta(baseline, incoming);
        if (delta.isEmpty()) {
            return;
        }
        answer.setLength(0);
        answer.append(ContentSegmentCoordinator.advanceBaseline(baseline, incoming, delta));
    }

    private String resolveSubagentOverlay() {
        String text = catalogHolder.snapshot().text(OVERLAY_CATALOG_ID).map(String::strip).orElse("");
        return StringUtils.hasText(text) ? text : null;
    }

    private void foldStepToken(
            String mainBridge, SpawnSubagentTimelineBridge subTimeline, StreamToken token) {
        if (token == null) {
            return;
        }
        // step / step_delta → subSteps；content → 父卡 result 流式（见 Bridge.wrap）
        if (token.isStep() || token.isStepDelta()
                || token.isContent() || token.isContentStart() || token.isContentEnd()) {
            timelineSupport.fold(mainBridge, subTimeline, token);
        }
    }

    /**
     * Registry.cancel 已下发父卡 paused 时不再二次 emit；
     * interrupt 未走 Registry 时经 {@link SpawnRunRegistry#flushCancelTerminal} 直写 GenerationJob。
     */
    private String cancelAndReturn(
            String runId, SpawnSubagentTimelineBridge subTimeline, String prompt) {
        String result = spawnRunRegistry.formatCancelResult(prompt);
        log.info("[SpawnSubagentTool] 子任务已取消，回主 Agent 接手");
        if (subTimeline != null && !subTimeline.userCancelled()) {
            spawnRunRegistry.flushCancelTerminal(runId, subTimeline, result);
        }
        return result;
    }

    private String failAndReturn(
            String mainBridge, SpawnSubagentTimelineBridge subTimeline, Throwable error) {
        String msg = error != null && StringUtils.hasText(error.getMessage())
                ? error.getMessage().strip()
                : "子 Agent 执行失败";
        log.warn("[SpawnSubagentTool] 子 Agent 失败: {}", msg);
        timelineSupport.fail(mainBridge, subTimeline, msg);
        return msg;
    }

    private AgentExecutionProperties.React.Subagent subagentConfig() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getSubagent() : null;
    }

    private static String stringParam(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.strip())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.strip())) {
                return false;
            }
        }
        return null;
    }

    private static boolean isTimeout(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof TimeoutException) {
            return true;
        }
        String name = t.getClass().getName();
        return name.contains("Timeout")
                || (t.getMessage() != null && t.getMessage().contains("Did not observe"));
    }

    private static boolean isInterrupted(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InterruptedException) {
                return true;
            }
            String name = cur.getClass().getSimpleName();
            if (name.contains("Interrupt")) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupt")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    /** 租户隔离：default 全局共享；租户私有智能体仅当前租户可 spawn */
    private static boolean canAccess(AgentCatalogEntry entry, String tenantId) {
        String entryTenant = entry.tenantId();
        if (entryTenant == null || entryTenant.isBlank() || "default".equals(entryTenant)) {
            return true;
        }
        return entryTenant.equals(tenantId);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 子 agent 工具 = agent.tools ∪ 各绑定 skill 的工具（去重；未知 skill 静默跳过） */
    private List<String> mergeAgentSkillTools(AgentCatalogEntry entry) {
        List<String> merged = new java.util.ArrayList<>(parseToolIds(entry.toolsJson()));
        if (entry.skillIds() != null) {
            for (String skillId : entry.skillIds()) {
                if (!StringUtils.hasText(skillId)) {
                    continue;
                }
                merged.addAll(skillCatalogService.toolIds(skillId));
            }
        }
        return merged.stream().distinct().toList();
    }

    private static List<String> parseToolIds(String toolsJson) {
        if (toolsJson == null || toolsJson.isBlank() || "[]".equals(toolsJson)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(toolsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[SpawnSubagentTool] 解析 toolsJson 失败: {}", e.getMessage());
            return List.of();
        }
    }
}
