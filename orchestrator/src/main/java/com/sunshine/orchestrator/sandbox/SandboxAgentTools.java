package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.AsyncToolRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.HitlWaitInterruptedException;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 沙箱六工具 AgentTool 提供者 — 不进 tool-manager Catalog；T12 注入 {@link #all()}。
 * 审计经 {@link ToolAuditService}；content/new_string/old_string 仅存 sha256，不落全文。
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class SandboxAgentTools {

    private static final Set<String> DIGEST_PARAM_KEYS = Set.of("content", "new_string", "old_string");

    private final SandboxClient sandboxClient;
    private final HitlConfirmationService hitlConfirmationService;
    private final ToolAuditService toolAuditService;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;
    private final AgentSandboxProperties sandboxProperties;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;
    private final PromptCatalogHolder promptCatalogHolder;
    private final AsyncToolRunRegistry asyncToolRunRegistry;
    private final AgentExecutionProperties executionProperties;

    private List<AgentTool> tools = List.of();

    @PostConstruct
    void init() {
        List<AgentTool> built = new ArrayList<>();
        for (String toolId : SandboxIds.ALL) {
            AgentSandboxProperties.ToolDef def = sandboxProperties.resolveTool(toolId);
            if (def == null) {
                log.warn("[SandboxAgentTools] 缺少工具定义: {}", toolId);
                continue;
            }
            built.add(tool(toolId, def.getDescription(), SandboxToolSchemas.toParameters(def)));
        }
        tools = List.copyOf(built);
    }

    public List<AgentTool> all() {
        return tools;
    }

    private AgentTool tool(String name, String description, Map<String, Object> parameters) {
        return new SandboxAgentTool(name, description, parameters);
    }

    private final class SandboxAgentTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> parameters;

        SandboxAgentTool(String name, String description, Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> execute(param))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private ToolResultBlock execute(ToolCallParam param) {
            String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
            Map<String, Object> body = extractBody(param);
            Map<String, String> hitlParams = toStringMap(body);
            String bridgeId = StepEventBridge.bridgeIdForToolUse(toolUseId);
            if (bridgeId == null) {
                bridgeId = StepEventBridge.resolveHitlBridgeId();
            }
            String generationMessageId = bridgeId != null ? StepEventBridge.hitlAssistantMessageId(bridgeId) : null;
            String messageId = generationMessageId != null
                    ? generationMessageId : StepEventBridge.activeMessageId();
            String invocationId = StringUtils.hasText(toolUseId) ? toolUseId.strip() : null;
            boolean trackCancel = cancellableToolRunRegistry.isCancellableTool(name)
                    && StringUtils.hasText(invocationId);
            // PreActing 已 register；此处补 expandDetail / 漏登记，并消费 pending cancel
            if (trackCancel) {
                String expandDetail = SandboxCancelExpand.detail(name, body);
                if (cancellableToolRunRegistry.get(invocationId) == null) {
                    if (StringUtils.hasText(messageId)) {
                        cancellableToolRunRegistry.register(
                                invocationId, messageId, name, null, invocationId, expandDetail);
                    }
                } else {
                    cancellableToolRunRegistry.bindExpandDetail(invocationId, expandDetail);
                }
                if (cancellableToolRunRegistry.isCancelled(invocationId)) {
                    return cancelResult(toolUseId, name, body, messageId, null, System.currentTimeMillis());
                }
            }
            long toolEpoch = generationMessageId != null
                    ? StepEventBridge.currentStreamEpoch(generationMessageId) : -1L;
            if (isStaleToolRun(generationMessageId, toolEpoch)) {
                if (trackCancel) {
                    cancellableToolRunRegistry.unregister(invocationId);
                }
                throw new HitlWaitInterruptedException();
            }
            if (shouldAwaitHitl(bridgeId, body)) {
                String preApproveMsgId = generationMessageId != null
                        ? generationMessageId : StepEventBridge.activeMessageId();
                if (preApproveMsgId != null
                        && StepEventBridge.consumeHitlPreApproval(preApproveMsgId, name, hitlParams)) {
                    log.info("[SandboxAgentTool] {} 续跑 re-await 已确认，跳过二次 HITL", name);
                } else {
                    try {
                        // 一轮多 tool_calls 时须按 toolUseId 精确定位 HITL 步（currentToolStepId 可能已被
                        // 其它工具覆盖），否则「等待用户确认」会误挂到同轮其它工具步（如 glob）。
                        String hitlStepId = StepEventBridge.stepIdForToolUse(toolUseId);
                        boolean approved = generationMessageId != null
                                ? hitlConfirmationService.awaitConfirmation(
                                        bridgeId, generationMessageId, name, hitlParams, hitlStepId)
                                : hitlConfirmationService.awaitConfirmation(bridgeId, name, hitlParams, hitlStepId);
                        if (!approved) {
                            if (trackCancel) {
                                cancellableToolRunRegistry.unregister(invocationId);
                            }
                            return denyResult(toolUseId, bridgeId, generationMessageId, body);
                        }
                    } catch (HitlWaitInterruptedException interrupted) {
                        if (trackCancel) {
                            cancellableToolRunRegistry.unregister(invocationId);
                        }
                        log.info("[SandboxAgentTool] {} HITL 等待被中断（暂停/续跑）", name);
                        throw interrupted;
                    }
                }
            }
            if (trackCancel && cancellableToolRunRegistry.isCancelled(invocationId)) {
                return cancelResult(toolUseId, name, body, messageId, null, System.currentTimeMillis());
            }
            if (isStaleToolRun(generationMessageId, toolEpoch)) {
                if (trackCancel) {
                    cancellableToolRunRegistry.unregister(invocationId);
                }
                throw new HitlWaitInterruptedException();
            }
            try {
                sandboxSessionLifecycle.ensureBound(bridgeId);
            } catch (Exception e) {
                if (trackCancel) {
                    cancellableToolRunRegistry.unregister(invocationId);
                }
                String err = StringUtils.hasText(e.getMessage())
                        ? e.getMessage() : "沙箱会话未就绪";
                log.warn("[SandboxAgentTool] ensureBound 失败: {}", err);
                auditIfBound(name, auditParams(body, null, null, null), err, "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(err).build());
            }
            if (cancellableToolRunRegistry.isCancellableTool(name)
                    && !cancellableToolRunRegistry.tryConsumeFollowup(messageId, name)) {
                if (trackCancel) {
                    cancellableToolRunRegistry.unregister(invocationId);
                }
                String exhausted = promptCatalogHolder.requireText("sandbox.budget-exhausted").strip();
                if (!StringUtils.hasText(exhausted)) {
                    exhausted = "本轮用户取消后同族沙箱工具调用次数已用尽，请直接作答或改用其它能力。";
                }
                auditIfBound(name, auditParams(body, null, null, null), exhausted, "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(exhausted).build());
            }
            String sessionId = SandboxSessionHolder.requireSessionId(bridgeId);
            String checkoutPath = sandboxSessionLifecycle.getCheckoutPath(bridgeId);
            if (StringUtils.hasText(checkoutPath)) {
                body.put("cwd", checkoutPath);
            }
            if (trackCancel) {
                cancellableToolRunRegistry.bindSession(invocationId, sessionId);
                if (cancellableToolRunRegistry.isCancelled(invocationId)) {
                    return cancelResult(toolUseId, name, body, messageId, sessionId, System.currentTimeMillis());
                }
            }
            log.info("[SandboxAgentTool] {} session={} bridge={} params={}",
                    name, sessionId, bridgeId, body.keySet());
            // background=true 仅 EXEC：立即返回 runId，invoke 在 boundedElastic 后台完成
            if (isBackgroundExec(body)) {
                return executeBackgroundExec(
                        toolUseId, body, messageId, sessionId, invocationId, trackCancel);
            }
            long startMs = System.currentTimeMillis();
            try {
                if (trackCancel && cancellableToolRunRegistry.isCancelled(invocationId)) {
                    return cancelResult(toolUseId, name, body, messageId, sessionId, startMs);
                }
                ToolInvokeResponse resp = sandboxClient.invoke(
                        sessionId, SandboxIds.rpcName(name), body, invocationId);
                if (trackCancel && (cancellableToolRunRegistry.isCancelled(invocationId)
                        || isCancelledResponse(resp))) {
                    return cancelResult(toolUseId, name, body, messageId, sessionId, startMs);
                }
                String output = resp != null && resp.output() != null ? resp.output() : "";
                boolean ok = resp != null && resp.ok();
                if (ok && SandboxIds.EDIT.equals(name) && resp.meta() != null) {
                    Object rawEditDiff = resp.meta().get("editDiff");
                    SandboxEditDiff parsed = SandboxEditDiffCodec.fromMeta(rawEditDiff);
                    if (parsed != null) {
                        SandboxEditDiffHolder.put(toolUseId, parsed);
                    }
                }
                Map<String, String> auditParams = auditParams(body, sessionId, resp, System.currentTimeMillis() - startMs);
                auditIfBound(name, auditParams, output, ok ? "ok" : "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(output).build());
            } catch (Exception e) {
                if (trackCancel && (cancellableToolRunRegistry.isCancelled(invocationId)
                        || isCancelException(e))) {
                    return cancelResult(toolUseId, name, body, messageId, sessionId, startMs);
                }
                log.warn("[SandboxAgentTool] {} 调用失败: {}", name, e.getMessage());
                // 透传 sandbox 原始 msg（如 path escapes jail），供模型改参重试；不做路径兼容改写
                String raw = e.getMessage();
                String err = StringUtils.hasText(raw) ? raw : "沙箱工具调用失败";
                Map<String, String> auditParams = auditParams(body, sessionId, null, System.currentTimeMillis() - startMs);
                auditIfBound(name, auditParams, err, "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(err).build());
            } finally {
                if (trackCancel) {
                    cancellableToolRunRegistry.unregister(invocationId);
                }
            }
        }

        private boolean isBackgroundExec(Map<String, Object> body) {
            return SandboxIds.EXEC.equals(name)
                    && asyncToolEnabled()
                    && Boolean.TRUE.equals(asBoolean(body.get("background")));
        }

        private ToolResultBlock executeBackgroundExec(
                String toolUseId,
                Map<String, Object> body,
                String messageId,
                String sessionId,
                String invocationId,
                boolean trackCancel) {
            if (!asyncToolRunRegistry.tryAcquireSlot(messageId)) {
                if (trackCancel) {
                    cancellableToolRunRegistry.unregister(invocationId);
                }
                return ToolResultBlock.of(
                        toolUseId,
                        name,
                        TextBlock.builder()
                                .text("{\"ok\":false,\"error\":\"本消息后台工具并发已达上限\"}")
                                .build());
            }
            String runId = StringUtils.hasText(invocationId) ? invocationId : UUID.randomUUID().toString();
            String conversationId = resolveConversationId(messageId);
            int wallSec = execWallTimeoutSec();
            long wallMs = wallSec * 1000L;
            // 编排语义勿下传 sandbox；未显式 timeout_sec 时对齐墙钟，避免默认 30s 掐死长命令
            Map<String, Object> invokeBody = SandboxAgentTools.prepareBackgroundExecInvokeBody(body, wallSec);
            // kill 回调与 register 原子绑定，避免墙钟先于 onCancelRequest 晚绑定
            Runnable onCancel = trackCancel
                    ? () -> cancellableToolRunRegistry.cancel(invocationId)
                    : null;
            asyncToolRunRegistry.registerWithId(
                    runId,
                    AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                    messageId,
                    conversationId,
                    wallMs,
                    onCancel);
            long startMs = System.currentTimeMillis();
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    if (trackCancel && cancellableToolRunRegistry.isCancelled(invocationId)) {
                        asyncToolRunRegistry.complete(
                                runId, AsyncToolRunRegistry.Status.CANCELLED, "已取消");
                        return;
                    }
                    ToolInvokeResponse resp = sandboxClient.invoke(
                            sessionId, SandboxIds.rpcName(name), invokeBody, invocationId);
                    if (trackCancel && (cancellableToolRunRegistry.isCancelled(invocationId)
                            || isCancelledResponse(resp))) {
                        asyncToolRunRegistry.complete(
                                runId, AsyncToolRunRegistry.Status.CANCELLED, "已取消");
                        return;
                    }
                    String output = resp != null && resp.output() != null ? resp.output() : "";
                    boolean ok = resp != null && resp.ok();
                    Map<String, String> auditParams =
                            auditParams(invokeBody, sessionId, resp, System.currentTimeMillis() - startMs);
                    auditIfBound(name, auditParams, output, ok ? "ok" : "fail");
                    asyncToolRunRegistry.complete(
                            runId,
                            ok ? AsyncToolRunRegistry.Status.DONE : AsyncToolRunRegistry.Status.ERROR,
                            output);
                } catch (Exception e) {
                    if (trackCancel && (cancellableToolRunRegistry.isCancelled(invocationId)
                            || isCancelException(e))) {
                        asyncToolRunRegistry.complete(
                                runId, AsyncToolRunRegistry.Status.CANCELLED, "已取消");
                        return;
                    }
                    log.warn("[SandboxAgentTool] {} background 调用失败: {}", name, e.getMessage());
                    String raw = e.getMessage();
                    String err = StringUtils.hasText(raw) ? raw : "沙箱工具调用失败";
                    Map<String, String> auditParams =
                            auditParams(invokeBody, sessionId, null, System.currentTimeMillis() - startMs);
                    auditIfBound(name, auditParams, err, "fail");
                    asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.ERROR, err);
                } finally {
                    if (trackCancel) {
                        cancellableToolRunRegistry.unregister(invocationId);
                    }
                }
            });
            return ToolResultBlock.of(
                    toolUseId,
                    name,
                    TextBlock.builder()
                            .text("{\"ok\":true,\"runId\":\"" + runId + "\",\"status\":\"running\"}")
                            .build());
        }

        private ToolResultBlock cancelResult(
                String toolUseId,
                String toolName,
                Map<String, Object> body,
                String messageId,
                String sessionId,
                long startMs) {
            int remaining = cancellableToolRunRegistry.activateBudgetAndRemaining(messageId);
            String params = summarizeParams(body);
            String tpl = promptCatalogHolder.requireText("sandbox.cancel-result").strip();
            if (!StringUtils.hasText(tpl)) {
                tpl = "用户已取消该沙箱工具调用。请换方案继续（勿重复同一命令）。原参数：{params}。本轮同族还可再调用 {remaining} 次。";
            }
            String text = tpl.replace("{params}", params)
                    .replace("{remaining}", String.valueOf(remaining));
            // 时间线 paused 由 GenerationController.cancelTool 单写；此处只回 ToolResult
            Map<String, String> auditParams = auditParams(body, sessionId, null, System.currentTimeMillis() - startMs);
            auditIfBound(toolName, auditParams, text, "cancelled");
            log.info("[SandboxAgentTool] {} 用户取消 toolUseId={} remaining={}", toolName, toolUseId, remaining);
            return ToolResultBlock.of(toolUseId, toolName, TextBlock.builder().text(text).build());
        }

        private static boolean isCancelledResponse(ToolInvokeResponse resp) {
            return resp != null
                    && resp.meta() != null
                    && Boolean.TRUE.equals(resp.meta().get("cancelled"));
        }

        private static boolean isCancelException(Throwable e) {
            Throwable cur = e;
            while (cur != null) {
                if (cur instanceof InterruptedException) {
                    return true;
                }
                String name = cur.getClass().getSimpleName();
                if (name.contains("Interrupt") || name.contains("Cancel")) {
                    return true;
                }
                cur = cur.getCause();
            }
            return false;
        }

        private static String summarizeParams(Map<String, Object> body) {
            if (body == null || body.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder();
            body.forEach((k, v) -> {
                if (k == null || DIGEST_PARAM_KEYS.contains(k)) {
                    return;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                String val = v != null ? String.valueOf(v) : "";
                if (val.length() > 120) {
                    val = val.substring(0, 120) + "…";
                }
                sb.append(k).append('=').append(val);
            });
            return sb.length() > 0 ? sb.toString() : "{}";
        }

        private boolean shouldAwaitHitl(String bridgeId, Map<String, Object> body) {
            if (hitlConfirmationService == null
                    || !hitlConfirmationService.shouldConfirmForBridge(name, bridgeId)) {
                return false;
            }
            String msgId = bridgeId != null ? StepEventBridge.hitlAssistantMessageId(bridgeId) : null;
            if (msgId == null) {
                msgId = StepEventBridge.activeMessageId();
            }
            SandboxWriteHitlMode mode = StepEventBridge.writeHitlMode(msgId);
            // Task 模式默认 SMART：write/edit 免确认，仅危险 exec 保留确认
            if (mode == SandboxWriteHitlMode.NEVER && isTaskSession()) {
                mode = SandboxWriteHitlMode.SMART;
            }
            return SandboxHitlPolicy.requiresConfirmation(name, body, mode);
        }

        private boolean isTaskSession() {
            SandboxSessionHolder.Binding binding = SandboxSessionHolder.current();
            if (binding == null || binding.policy() == null) {
                return false;
            }
            return "task".equals(binding.policy().kind());
        }

        private ToolResultBlock denyResult(
                String toolUseId, String bridgeId, String generationMessageId, Map<String, Object> body) {
            if (bridgeId != null) {
                StepEventBridge.emit(bridgeId, session -> session.skipCurrentToolStep(
                        hitlConfirmationService.skippedAfterSummary()));
                String flushId = generationMessageId != null ? generationMessageId : bridgeId;
                hitlConfirmationService.flushTimeline(flushId);
            }
            String rejection = hitlConfirmationService.rejectionMessage();
            String sessionId = null;
            SandboxSessionHolder.Binding binding = SandboxSessionHolder.get(bridgeId);
            if (binding == null) {
                binding = SandboxSessionHolder.current();
            }
            if (binding != null) {
                sessionId = binding.sessionId();
            }
            auditIfBound(name, auditParams(body, sessionId, null, null), rejection, "skipped");
            return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(rejection).build());
        }
    }

    private void auditIfBound(String toolId, Map<String, String> params, String output, String status) {
        String messageId = StepEventBridge.activeMessageId();
        StepEventBridge.ToolAuditContext ctx = StepEventBridge.toolAuditContext(messageId);
        if (ctx == null || toolAuditService == null) {
            return;
        }
        String summary = output != null && output.length() > 240 ? output.substring(0, 240) + "..." : output;
        toolAuditService.toolCall(
                ctx.conversationId(),
                ctx.messageId(),
                ctx.userId(),
                ctx.tenantId(),
                ctx.planId(),
                null,
                toolId,
                params,
                summary != null ? summary : "",
                status);
    }

    private boolean asyncToolEnabled() {
        AgentExecutionProperties.React react = executionProperties != null ? executionProperties.getReact() : null;
        AgentExecutionProperties.React.AsyncTool cfg = react != null ? react.getAsyncTool() : null;
        return cfg == null || cfg.isEnabled();
    }

    private int execWallTimeoutSec() {
        AgentExecutionProperties.React react = executionProperties != null ? executionProperties.getReact() : null;
        AgentExecutionProperties.React.AsyncTool cfg = react != null ? react.getAsyncTool() : null;
        int sec = cfg != null ? cfg.getExecWallTimeoutSec() : 600;
        return sec > 0 ? sec : 600;
    }

    /**
     * 后台 exec 下传 sandbox 的 body：去掉 background；未显式 timeout_sec 时对齐墙钟，
     * 避免 sandbox 默认 30s 先于 await 预算掐断长命令。
     */
    static Map<String, Object> prepareBackgroundExecInvokeBody(Map<String, Object> body, int wallSec) {
        Map<String, Object> invokeBody = body != null ? new LinkedHashMap<>(body) : new LinkedHashMap<>();
        invokeBody.remove("background");
        int wall = wallSec > 0 ? wallSec : 600;
        Object rawTimeout = invokeBody.get("timeout_sec");
        Integer timeoutSec = null;
        if (rawTimeout instanceof Number n) {
            timeoutSec = n.intValue();
        } else if (rawTimeout instanceof String s && StringUtils.hasText(s)) {
            try {
                timeoutSec = Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                timeoutSec = null;
            }
        }
        if (timeoutSec == null || timeoutSec <= 0) {
            invokeBody.put("timeout_sec", wall);
        }
        return invokeBody;
    }

    private static String resolveConversationId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return null;
        }
        StepEventBridge.ToolAuditContext ctx = StepEventBridge.toolAuditContext(messageId);
        return ctx != null ? ctx.conversationId() : null;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Boolean.parseBoolean(s.strip());
        }
        return null;
    }

    /**
     * 审计 params：敏感正文字段替换为 sha256 hex；附带 sessionId / exitCode / durationMs。
     */
    static Map<String, String> auditParams(
            Map<String, Object> body, String sessionId, ToolInvokeResponse resp, Long durationMs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) {
            out.put("sessionId", sessionId);
        }
        if (body != null) {
            body.forEach((k, v) -> {
                if (k == null) {
                    return;
                }
                String raw = v != null ? String.valueOf(v) : "";
                out.put(k, DIGEST_PARAM_KEYS.contains(k) ? sha256Hex(raw) : raw);
            });
        }
        if (resp != null && resp.exitCode() != null) {
            out.put("exitCode", String.valueOf(resp.exitCode()));
        }
        if (durationMs != null) {
            out.put("durationMs", String.valueOf(durationMs));
        }
        return out;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean isStaleToolRun(String generationMessageId, long toolEpoch) {
        return generationMessageId != null && toolEpoch >= 0
                && !StepEventBridge.isStreamEpochValid(generationMessageId, toolEpoch);
    }

    private static Map<String, Object> extractBody(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        input.forEach((k, v) -> {
            if (k != null && v != null) {
                body.put(k, v);
            }
        });
        return body;
    }

    private static Map<String, String> toStringMap(Map<String, Object> body) {
        Map<String, String> out = new LinkedHashMap<>();
        body.forEach((k, v) -> out.put(k, v != null ? String.valueOf(v) : ""));
        return out;
    }
}
