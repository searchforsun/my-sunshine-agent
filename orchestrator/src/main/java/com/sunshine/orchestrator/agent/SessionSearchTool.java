package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级正文恢复工具（M3）+ 工作区跨会话扩展（D8）。
 * scope=session：检索**本会话**早前轮次的对话正文（body 层），scope=session 由 rag-service 侧 convId 等值过滤；
 * scope=workspace：检索**当前工作区其他 task 会话**的正文（跨会话续接），由会话列表 convId IN 过滤——
 * 从当前会话反查 workspaceId → 展开该工作区全部 task 会话 id（排除当前会话，截断至
 * react.session-search.workspace-max-convs）→ rag-service 按会话列表检索。
 * 检索复用 L3 Milvus {@code sunshine_chat_history}，scene=task 隔离 + layer=body|process；
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
    private final ChatConversationRepository conversationRepo;
    private final AgentExecutionProperties executionProperties;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "检索任务历史正文：scope=session 查本会话早前轮次，scope=workspace 查当前工作区其他任务会话（跨会话续接）。"
                + "当需要确认本会话之前讨论过、约定过、验证过的细节，或续接同一工作区其他任务会话的进度"
                + "而当前上下文未体现时调用此工具深挖原文。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "自然语言查询文本，用于匹配任务历史正文"));
        props.put("scope", Map.of(
                "type", "string",
                "enum", List.of("session", "workspace"),
                "description", "检索范围：session=本会话（缺省）；workspace=当前工作区其他任务会话"));
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
        if (StringUtils.hasText(scope)) {
            String scopeValue = scope.strip();
            if (!"session".equals(scopeValue) && !"workspace".equals(scopeValue)) {
                return ToolResultBlock.of(toolUseId, NAME,
                        TextBlock.builder().text("调用失败：scope 仅支持 session（本会话）或 workspace（工作区跨会话）").build());
            }
            if ("workspace".equals(scopeValue)) {
                return executeWorkspace(toolUseId, query, audit);
            }
        }
        return executeSession(toolUseId, query, audit);
    }

    private ToolResultBlock executeSession(String toolUseId, String query, StepEventBridge.ToolAuditContext audit) {
        if (audit == null || !StringUtils.hasText(audit.conversationId())) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("调用失败：缺少会话上下文（conversationId）").build());
        }
        log.info("[SessionSearchTool] 本会话正文检索 query='{}' conv={}",
                query.length() > 50 ? query.substring(0, 50) + "..." : query, audit.conversationId());
        try {
            List<HistoryRagClient.HistoryHit> hits = historyRagClient
                    .search(audit.userId(), audit.tenantId(), audit.conversationId(), "task",
                            List.of("body", "process"), query, topK())
                    .block();
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text(formatHits("本会话", hits)).build());
        } catch (Exception e) {
            log.warn("[SessionSearchTool] 本会话检索失败 conv={}: {}",
                    audit.conversationId(), e.getMessage());
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("工具调用失败：" + e.getMessage()).build());
        }
    }

    private ToolResultBlock executeWorkspace(String toolUseId, String query, StepEventBridge.ToolAuditContext audit) {
        if (audit == null || !StringUtils.hasText(audit.conversationId())) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("调用失败：缺少会话上下文（conversationId）").build());
        }
        ChatConversationEntity conv = conversationRepo.findById(audit.conversationId()).orElse(null);
        if (conv == null || !StringUtils.hasText(conv.getWorkspaceId())) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("调用失败：当前任务会话未绑定工作区，无法跨会话检索（可改用 scope=session 查本会话）")
                            .build());
        }
        List<String> convIds = conversationRepo.findTaskIdsByWorkspace(
                audit.userId(), audit.tenantId(), conv.getWorkspaceId());
        List<String> scoped = new ArrayList<>();
        if (convIds != null) {
            for (String id : convIds) {
                // 跨会话语义纯净：排除当前会话（本会话用 scope=session 查）
                if (id != null && !id.equals(audit.conversationId())) {
                    scoped.add(id);
                }
            }
        }
        if (scoped.isEmpty()) {
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("工作区暂无其他任务会话历史，可改用 scope=session 查本会话").build());
        }
        int max = workspaceMaxConvs();
        if (scoped.size() > max) {
            scoped = new ArrayList<>(scoped.subList(0, max));
        }
        log.info("[SessionSearchTool] 工作区跨会话检索 query='{}' workspace={} convs={}",
                query.length() > 50 ? query.substring(0, 50) + "..." : query,
                conv.getWorkspaceId(), scoped.size());
        try {
            List<HistoryRagClient.HistoryHit> hits = historyRagClient
                    .search(audit.userId(), audit.tenantId(), scoped, "task",
                            List.of("body", "process"), query, topK())
                    .block();
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text(formatHits("工作区其他任务会话", hits)).build());
        } catch (Exception e) {
            log.warn("[SessionSearchTool] 工作区检索失败 workspace={}: {}",
                    conv.getWorkspaceId(), e.getMessage());
            return ToolResultBlock.of(toolUseId, NAME,
                    TextBlock.builder().text("工具调用失败：" + e.getMessage()).build());
        }
    }

    private int topK() {
        return contextProperties.getL3() != null
                ? Math.max(1, contextProperties.getL3().getTopK())
                : 5;
    }

    private int workspaceMaxConvs() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        if (react != null && react.getSessionSearch() != null
                && react.getSessionSearch().getWorkspaceMaxConvs() > 0) {
            return react.getSessionSearch().getWorkspaceMaxConvs();
        }
        return 20;
    }

    private static String formatHits(String label, List<HistoryRagClient.HistoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return label + "未检索到相关历史记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("查找到").append(label).append("历史记录 ").append(hits.size()).append(" 条：");
        for (HistoryRagClient.HistoryHit h : hits) {
            String content = h.content() != null ? h.content().strip() : "";
            if (!StringUtils.hasText(content)) {
                continue;
            }
            sb.append("\n- ").append(content);
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
