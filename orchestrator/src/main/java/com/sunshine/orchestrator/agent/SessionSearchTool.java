package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 会话级正文恢复工具 — 检索**本会话**（scope=session）早前轮次的对话正文（body 层），
 * 供模型在 Near/Mid/Far、任务清单、KV todo 注入仍不足以回答「本会话早前说过/做过什么」时按需深挖。
 * 检索复用 L3 Milvus {@code sunshine_chat_history}，scope=session 由 rag-service 侧 convId 过滤实现；
 * 仅注册到 task 会话 MAIN（chat/workflow/SUB/PLANNER 不注册）。toolAuditContext 绑定于主消息，
 * 无 conversationId 时返回错误说明（不抛异常）。名称加 sunshine_ 前缀区别于 AgentScope 原生 session_search。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSearchTool implements AgentTool {

    public static final String NAME = "sunshine_session_search";

    private final HistoryRagClient historyRagClient;
    private final ContextProperties contextProperties;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "检索当前任务会话早前轮次的对话正文（仅本会话，不含其他会话内容）。"
                + "当需要确认本会话之前讨论过、约定过、验证过的细节，而当前上下文未体现时调用此工具深挖原文。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "自然语言查询文本，用于匹配本会话历史正文"));
        props.put("scope", Map.of(
                "type", "string",
                "enum", List.of("session"),
                "description", "检索范围，一期仅支持 session（本会话），缺省 session"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("query"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        StepEventBridge.ToolAuditContext audit = auditContext();
        return execute(param, audit);
    }

    /** 单测入口：audit 可注入（生产路径从 StepEventBridge 静态取） */
    ToolResultBlock execute(ToolCallParam param, StepEventBridge.ToolAuditContext audit) {
        String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
        String query = stringParam(param, "query");
        String scope = stringParam(param, "scope");
        if (!StringUtils.hasText(query)) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("调用失败：query 不能为空").build());
        }
        if (StringUtils.hasText(scope) && !"session".equals(scope.strip())) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("调用失败：一期仅支持 scope=session").build());
        }
        if (audit == null || !StringUtils.hasText(audit.conversationId())) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("调用失败：缺少会话上下文（conversationId）").build());
        }
        log.info("[SessionSearchTool] 本会话正文检索 query='{}' conv={}",
                query.length() > 50 ? query.substring(0, 50) + "..." : query, audit.conversationId());
        try {
            int topK = contextProperties.getL3() != null
                    ? Math.max(1, contextProperties.getL3().getTopK())
                    : 5;
            List<HistoryRagClient.HistoryHit> hits = historyRagClient
                    .search(audit.userId(), audit.tenantId(), audit.conversationId(), query, topK)
                    .block();
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text(formatHits(hits)).build());
        } catch (Exception e) {
            log.warn("[SessionSearchTool] 本会话检索失败 conv={}: {}",
                    audit.conversationId(), e.getMessage());
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("工具调用失败：" + e.getMessage()).build());
        }
    }

    private static String formatHits(List<HistoryRagClient.HistoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "本会话未检索到相关历史记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("查找到本会话历史记录 ").append(hits.size()).append(" 条：");
        int idx = 1;
        for (HistoryRagClient.HistoryHit h : hits) {
            String content = h.content() != null ? h.content().strip() : "";
            if (!StringUtils.hasText(content)) {
                continue;
            }
            sb.append("\n- ").append(content);
            idx++;
        }
        return sb.toString();
    }

    private static String stringParam(ToolCallParam param, String key) {
        Map<String, Object> input = param.getInput();
        if (input == null || !input.containsKey(key) || input.get(key) == null) {
            return null;
        }
        return String.valueOf(input.get(key));
    }

    private static StepEventBridge.ToolAuditContext auditContext() {
        String messageId = StepEventBridge.activeMessageId();
        if (messageId == null) {
            return null;
        }
        return StepEventBridge.toolAuditContext(messageId);
    }
}
