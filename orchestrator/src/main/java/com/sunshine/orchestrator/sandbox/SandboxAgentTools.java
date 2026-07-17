package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.client.sandbox.ToolInvokeResponse;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.HitlWaitInterruptedException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 沙箱六工具 AgentTool 提供者 — 不进 tool-manager Catalog；T12 注入 {@link #all()}。
 * 审计经 {@link ToolAuditService}；content/new_string/old_string 仅存 sha256，不落全文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxAgentTools {

    private static final Set<String> DIGEST_PARAM_KEYS = Set.of("content", "new_string", "old_string");

    private final SandboxClient sandboxClient;
    private final HitlConfirmationService hitlConfirmationService;
    private final ToolAuditService toolAuditService;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;

    private List<AgentTool> tools = List.of();

    @PostConstruct
    void init() {
        tools = List.of(
                tool(SandboxIds.READ, "读取沙箱内文本文件（/skills/{skillId}/... 或 /workspace；勿对目录调用）", readSchema()),
                tool(SandboxIds.WRITE, "仅新建工作区文件（仅 /workspace；已存在则失败，请改用 edit 或换路径）", writeSchema()),
                tool(SandboxIds.EDIT, "精确替换已有工作区文件中的唯一子串（仅 /workspace）", editSchema()),
                tool(SandboxIds.GLOB, "在沙箱 jail 内按 glob 查找文件路径（优先收窄 path/pattern）", globSchema()),
                tool(SandboxIds.GREP, "在沙箱 jail 内按正则搜索文件内容（须提供 pattern）", grepSchema()),
                tool(SandboxIds.EXEC, "在沙箱容器内执行 shell（破坏性命令会被拒绝；只读命令通常免 HITL）", execSchema()));
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
            long toolEpoch = generationMessageId != null
                    ? StepEventBridge.currentStreamEpoch(generationMessageId) : -1L;
            if (isStaleToolRun(generationMessageId, toolEpoch)) {
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
                        boolean approved = generationMessageId != null
                                ? hitlConfirmationService.awaitConfirmation(
                                        bridgeId, generationMessageId, name, hitlParams)
                                : hitlConfirmationService.awaitConfirmation(bridgeId, name, hitlParams);
                        if (!approved) {
                            return denyResult(toolUseId, bridgeId, generationMessageId, body);
                        }
                    } catch (HitlWaitInterruptedException interrupted) {
                        log.info("[SandboxAgentTool] {} HITL 等待被中断（暂停/续跑）", name);
                        throw interrupted;
                    }
                }
            }
            if (isStaleToolRun(generationMessageId, toolEpoch)) {
                throw new HitlWaitInterruptedException();
            }
            try {
                sandboxSessionLifecycle.ensureBound(bridgeId);
            } catch (Exception e) {
                String err = StringUtils.hasText(e.getMessage())
                        ? e.getMessage() : "沙箱会话未就绪";
                log.warn("[SandboxAgentTool] ensureBound 失败: {}", err);
                auditIfBound(name, auditParams(body, null, null, null), err, "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(err).build());
            }
            String sessionId = SandboxSessionHolder.requireSessionId(bridgeId);
            log.info("[SandboxAgentTool] {} session={} bridge={} params={}",
                    name, sessionId, bridgeId, body.keySet());
            long startMs = System.currentTimeMillis();
            try {
                ToolInvokeResponse resp = sandboxClient.invoke(sessionId, SandboxIds.rpcName(name), body);
                String output = resp != null && resp.output() != null ? resp.output() : "";
                boolean ok = resp != null && resp.ok();
                Map<String, String> auditParams = auditParams(body, sessionId, resp, System.currentTimeMillis() - startMs);
                auditIfBound(name, auditParams, output, ok ? "ok" : "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(output).build());
            } catch (Exception e) {
                log.warn("[SandboxAgentTool] {} 调用失败: {}", name, e.getMessage());
                // 透传 sandbox 原始 msg（如 path escapes jail），供模型改参重试；不做路径兼容改写
                String raw = e.getMessage();
                String err = StringUtils.hasText(raw) ? raw : "沙箱工具调用失败";
                Map<String, String> auditParams = auditParams(body, sessionId, null, System.currentTimeMillis() - startMs);
                auditIfBound(name, auditParams, err, "fail");
                return ToolResultBlock.of(toolUseId, name, TextBlock.builder().text(err).build());
            }
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
            return SandboxHitlPolicy.requiresConfirmation(name, body, mode);
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

    private static Map<String, Object> readSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string",
                "description", "文件路径（须 /skills/{skillId}/... 或 /workspace/...；禁止旧路径 /skill）"));
        props.put("offset", Map.of("type", "integer", "description", "起始行（可选）"));
        props.put("limit", Map.of("type", "integer", "description", "读取行数上限（可选）"));
        return schema(props, List.of("path"));
    }

    private static Map<String, Object> writeSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string",
                "description", "写入路径（仅 /workspace）；仅允许新建，禁止覆盖已有文件"));
        props.put("content", Map.of("type", "string", "description", "新建文件全文内容"));
        return schema(props, List.of("path", "content"));
    }

    private static Map<String, Object> editSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "已有文件路径（仅 /workspace）"));
        props.put("old_string", Map.of("type", "string", "description", "待替换的精确原文（须在文件中唯一出现）"));
        props.put("new_string", Map.of("type", "string", "description", "替换后的文本"));
        return schema(props, List.of("path", "old_string", "new_string"));
    }

    private static Map<String, Object> globSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "glob 模式，如 **/*.py；尽量收窄"));
        props.put("path", Map.of("type", "string",
                "description", "搜索根（可选）：须为 /skills/{skillId}/... 或 /workspace；禁止 /skill；缺省搜全部 jail"));
        return schema(props, List.of("pattern"));
    }

    private static Map<String, Object> grepSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "搜索正则（必填）；避免空模式"));
        props.put("path", Map.of("type", "string",
                "description", "搜索路径（可选）：须为 /skills/{skillId}/... 或 /workspace；禁止 /skill"));
        props.put("glob", Map.of("type", "string", "description", "文件名 glob 过滤（可选）"));
        return schema(props, List.of("pattern"));
    }

    private static Map<String, Object> execSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Map.of("type", "string",
                "description", "shell 命令；禁止 rm -rf /、管道下载执行、mkfs 等破坏性操作"));
        props.put("cwd", Map.of("type", "string",
                "description", "工作目录（可选，默认 /workspace；须在 /skills/{skillId}/... 或 /workspace）"));
        props.put("timeout_sec", Map.of("type", "integer", "description", "超时秒数（可选，默认 30）"));
        return schema(props, List.of("command"));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(required));
        return schema;
    }
}
