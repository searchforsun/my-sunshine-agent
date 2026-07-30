package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.RagContextFormatter;
import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.rag.DefaultKbResolver;
import com.sunshine.orchestrator.rag.RagSearch;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库检索工具 — AgentTool 形态以获取 toolUseId，并行 RAG 改写 trace 按次切片。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTool implements AgentTool {

    public static final String NAME = "search_knowledge";

    private final RagClient ragClient;
    private final DefaultKbResolver defaultKbResolver;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "搜索企业知识库获取相关文档。当用户询问专业知识、公司政策、"
                + "技术规范、操作手册等需要参考文档的问题时，应优先调用此工具进行检索。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "自然语言查询文本，将用于向量检索匹配相关文档片段"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("query"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
        String query = stringParam(param, "query");
        log.info("[RagTool] Agent 调用知识库检索: query='{}'",
                query != null && query.length() > 50 ? query.substring(0, 50) + "..." : query);
        String messageId = StepEventBridge.activeMessageId();
        String ragStepId = StepEventBridge.stepIdForToolUse(toolUseId);
        try {
            var results = RagSearch.searchBlocking(
                    ragClient,
                    defaultKbResolver,
                    query,
                    null,
                    resolveKbId(),
                    resolveTenantId(),
                    messageId,
                    ragStepId);
            String text = RagContextFormatter.formatToolResult(results);
            return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
        } catch (Exception e) {
            log.warn("[RagTool] 知识库检索失败: {}", e.getMessage());
            String err = "工具调用失败：知识库服务不可用（" + e.getMessage()
                    + "）。请如实告知用户当前无法检索企业知识库。";
            return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(err).build());
        }
    }

    private static String stringParam(ToolCallParam param, String key) {
        Map<String, Object> input = param.getInput();
        if (input == null || !input.containsKey(key) || input.get(key) == null) {
            return null;
        }
        return String.valueOf(input.get(key));
    }

    private static String resolveTenantId() {
        StepEventBridge.ToolAuditContext ctx = auditContext();
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            return "default";
        }
        return ctx.tenantId().strip();
    }

    private static String resolveKbId() {
        StepEventBridge.ToolAuditContext ctx = auditContext();
        if (ctx == null) {
            return null;
        }
        if (ctx.kbScope() != null && !ctx.kbScope().isEmpty()) {
            if (ctx.kbScope().size() == 1 && "*".equals(ctx.kbScope().get(0))) {
                return ctx.kbId() != null ? ctx.kbId().strip() : null;
            }
            return ctx.kbScope().get(0);
        }
        if (ctx.kbId() == null || ctx.kbId().isBlank()) {
            return null;
        }
        return ctx.kbId().strip();
    }

    private static StepEventBridge.ToolAuditContext auditContext() {
        String messageId = StepEventBridge.activeMessageId();
        if (messageId == null) {
            return null;
        }
        return StepEventBridge.toolAuditContext(messageId);
    }
}
