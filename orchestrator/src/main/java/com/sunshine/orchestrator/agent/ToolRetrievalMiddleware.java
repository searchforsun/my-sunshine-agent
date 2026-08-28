package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/**
 * 5.5 工具语义检索注入中间件（tool RAG，仅 MAIN 生效）：
 * 每轮 reasoning 按最近上下文检索 Top-K 工具，把激活组写入 {@code state.getToolContext()}
 * —— AgentScope 每轮从激活组解析 tools schema（非激活分组工具不渲染），本轮决定下一轮可用工具。
 * 检索失败/索引关闭 → {@link ToolRetrievalService#fallbackToolIds} 回退全量注入，能力不回退。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRetrievalMiddleware implements MiddlewareBase {

    /** RuntimeContext 注入：当前租户（检索隔离） */
    public static final String CTX_TENANT_ID = "sunshine.tenantId";
    /** RuntimeContext 注入：会话 kind（chat|task，工具集定位） */
    public static final String CTX_CONVERSATION_KIND = "sunshine.conversationKind";

    private static final int QUERY_MAX_CHARS = 400;

    private final ToolRetrievalService toolRetrievalService;
    private final AgentExecutionProperties executionProperties;

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (ctx == null || !shouldApply(ctx) || input.messages() == null || input.messages().isEmpty()) {
            return next.apply(input);
        }
        String query = extractQuery(input.messages());
        if (query.isBlank()) {
            return next.apply(input);
        }
        String tenantId = ctx.get(CTX_TENANT_ID);
        String conversationKind = ctx.get(CTX_CONVERSATION_KIND);
        return Mono.fromCallable(() -> {
            applyRetrievedGroups(ctx, query, tenantId, conversationKind);
            return Boolean.TRUE;
        }).subscribeOn(VirtualThreadExecutors.scheduler())
                .thenMany(next.apply(input));
    }

    private boolean shouldApply(RuntimeContext ctx) {
        if (!toolRetrievalService.retrievalEnabled()) {
            return false;
        }
        Object role = ctx.get(ProcessingStepMiddleware.CTX_AGENT_ROLE);
        return role == null || "MAIN".equals(String.valueOf(role));
    }

    private void applyRetrievedGroups(RuntimeContext ctx, String query, String tenantId, String conversationKind) {
        if (ctx.getAgentState() == null || ctx.getAgentState().getToolContext() == null) {
            return;
        }
        AgentExecutionProperties.React.ToolInject inject = executionProperties.getReact().getToolInject();
        int topK = inject != null && inject.getTopK() > 0 ? inject.getTopK() : 8;
        List<String> ids;
        try {
            ids = toolRetrievalService.searchToolIds(query, tenantId, conversationKind, topK);
        } catch (Exception e) {
            log.warn("[ToolRetrieval] 检索失败回退全量注入 tenant={}: {}", tenantId, e.getMessage());
            ids = fallback(toolRetrievalService, tenantId, conversationKind, inject);
        }
        if (ids.isEmpty() && inject != null && inject.isFallbackFull()) {
            ids = toolRetrievalService.fallbackToolIds(tenantId, conversationKind);
        }
        List<String> groups = ids.stream().map(ToolRetrievalService::groupOf).toList();
        ctx.getAgentState().getToolContext().setActivatedGroups(groups);
        if (!ids.isEmpty()) {
            log.info("[ToolRetrieval] 注入 Top-K 工具: {}（query={}）", ids, brief(query));
        }
    }

    private static List<String> fallback(
            ToolRetrievalService service, String tenantId, String conversationKind,
            AgentExecutionProperties.React.ToolInject inject) {
        return inject != null && inject.isFallbackFull()
                ? service.fallbackToolIds(tenantId, conversationKind)
                : List.of();
    }

    /** 检索 query：最近一条 USER 消息全文（ReAct 每轮意图主体）；无 USER 时用末条消息兜底。 */
    static String extractQuery(List<Msg> messages) {
        String lastUser = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.getRole() == MsgRole.USER) {
                lastUser = m.getTextContent();
                break;
            }
        }
        String base = StringUtils.hasText(lastUser) ? lastUser : messages.get(messages.size() - 1).getTextContent();
        if (base == null) {
            return "";
        }
        String collapsed = base.strip();
        return collapsed.length() <= QUERY_MAX_CHARS
                ? collapsed
                : collapsed.substring(0, QUERY_MAX_CHARS);
    }

    private static String brief(String query) {
        return query.length() <= 30 ? query : query.substring(0, 30) + "...";
    }
}
