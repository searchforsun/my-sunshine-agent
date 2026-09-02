package com.sunshine.orchestrator.agent;

import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.ToolRetrievalClient;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 5.5 工具语义检索注入核心 — MAIN 主链按 query 检索 Top-K 工具注入（Tier 2 schema），
 * 全量工具名列表进 Tier 0 稳定前缀。职责：
 * <ul>
 *   <li>检索 Top-K：调 rag-service tool-index，结果收敛到当前 (tenant, kind) 可检索业务工具集；</li>
 *   <li>恒注入判定（5.5.4）：HITL（require_confirmation）/ 沙箱 / 内置元工具始终可见，不参与检索过滤；</li>
 *   <li>索引同步（5.5.2）：工具目录内容指纹变化时全量重建 rag-service 索引；</li>
 *   <li>Tier 0 目录渲染：全量工具名列表（确定性排序，字节稳定）。</li>
 * </ul>
 * 工具组名约定 {@code tool:{toolId}}：retrieval 模式下业务工具按组注册，激活组决定可见 schema。
 */
@Slf4j
@Service
public class ToolRetrievalService {

    private static final String GROUP_PREFIX = "tool:";

    /** 内置元工具（不经 tool-service catalog；MAIN toolkit 直接注册，未分组恒可见） */
    private static final Set<String> BUILTIN_TOOLS = Set.of(
            "search_knowledge", "think_summary", "spawn_subagent", "request_decision",
            "await_tool_run", "async_status", "session_search", "sunshine_search_skills",
            "dispatch_worker", "plan_submit", "self_assess", "task_status");

    private final ToolRetrievalClient client;
    private final ToolCatalogService toolCatalogService;
    private final ToolSetResolver toolSetResolver;
    private final AgentSandboxProperties sandboxProperties;
    private final AgentExecutionProperties executionProperties;

    /** tenant → 已同步索引的工具目录内容指纹（避免目录未变时的重复全量重建） */
    private volatile String syncedFingerprint = "";

    public ToolRetrievalService(
            ToolRetrievalClient client,
            ToolCatalogService toolCatalogService,
            ToolSetResolver toolSetResolver,
            AgentSandboxProperties sandboxProperties,
            AgentExecutionProperties executionProperties) {
        this.client = client;
        this.toolCatalogService = toolCatalogService;
        this.toolSetResolver = toolSetResolver;
        this.sandboxProperties = sandboxProperties;
        this.executionProperties = executionProperties;
    }

    /** retrieval 分层注入是否对 MAIN 生效（Nacos agent.execution.react.tool-inject.mode=retrieval） */
    public boolean retrievalEnabled() {
        AgentExecutionProperties.React.ToolInject inject = executionProperties.getReact().getToolInject();
        return inject != null && "retrieval".equalsIgnoreCase(inject.getMode());
    }

    /** 恒注入工具（5.5.4）：HITL / 沙箱 / 内置元工具始终可见，不参与检索过滤 */
    public boolean isAlwaysInject(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return false;
        }
        String id = toolId.strip();
        if (BUILTIN_TOOLS.contains(id)) {
            return true;
        }
        if (sandboxProperties.isSandboxTool(id)) {
            return true;
        }
        return toolCatalogService.requiresConfirmation(id);
    }

    /** (tenant, kind) 默认工具集内可检索的业务工具（排除恒注入） */
    public List<String> searchableToolIds(String tenantId, String conversationKind) {
        return toolSetResolver.resolveDefaultTools(tenantId, conversationKind).stream()
                .filter(id -> !isAlwaysInject(id))
                .toList();
    }

    /** 检索 Top-K 工具 id：命中去重且收敛到可检索业务工具集，按相似度保序。 */
    public List<String> searchToolIds(String query, String tenantId, String conversationKind, int topK) {
        ensureIndexSynced(tenantId);
        List<String> searchable = searchableToolIds(tenantId, conversationKind);
        if (searchable.isEmpty()) {
            return List.of();
        }
        List<ToolRetrievalClient.ToolIndexHit> hits = client.search(query, topK, tenantId).block();
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Set<String> pool = new HashSet<>(searchable);
        List<String> out = new ArrayList<>();
        for (ToolRetrievalClient.ToolIndexHit hit : hits) {
            if (pool.contains(hit.toolId()) && !out.contains(hit.toolId())) {
                out.add(hit.toolId());
            }
        }
        return out;
    }

    /** 检索失败/空 → 回退全量业务工具（Nacos fallback-full），保证 ReAct 能力不回退。 */
    public List<String> fallbackToolIds(String tenantId, String conversationKind) {
        return searchableToolIds(tenantId, conversationKind);
    }

    /** Tier 0 全量工具名目录（确定性 id 排序，字节稳定）；仅列默认工具集内工具。 */
    public String renderToolDirectory(String tenantId, String conversationKind) {
        List<String> tools = toolSetResolver.resolveDefaultTools(tenantId, conversationKind);
        List<String> sorted = tools.stream().sorted().toList();
        StringBuilder sb = new StringBuilder();
        for (String toolId : sorted) {
            sb.append("- **").append(toolId).append("**");
            ToolCatalogEntry entry = toolCatalogService.find(toolId).orElse(null);
            if (entry != null && StringUtils.hasText(entry.displayName())) {
                sb.append(' ').append(entry.displayName().strip());
            }
            if (entry != null && StringUtils.hasText(entry.description())) {
                sb.append("：").append(oneLine(entry.description().strip()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 工具组名（retrieval 模式业务工具按组注册） */
    public static String groupOf(String toolId) {
        return GROUP_PREFIX + toolId;
    }

    /** 工具目录内容指纹：id+description 拼接（工具内容变化触发索引重建） */
    private String catalogFingerprint(String tenantId) {
        List<String> tools = toolSetResolver.resolveDefaultTools(tenantId, null);
        StringBuilder sb = new StringBuilder();
        for (String toolId : tools.stream().sorted().toList()) {
            sb.append(toolId).append('|');
            ToolCatalogEntry entry = toolCatalogService.find(toolId).orElse(null);
            if (entry != null) {
                sb.append(entry.displayName() == null ? "" : entry.displayName())
                        .append('|')
                        .append(entry.description() == null ? "" : entry.description());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 索引惰性同步：目录内容指纹变化时全量重建 rag-service 索引（5.5.2）。 */
    private void ensureIndexSynced(String tenantId) {
        String fp = catalogFingerprint(tenantId);
        if (fp.equals(syncedFingerprint)) {
            return;
        }
        try {
            List<ToolRetrievalClient.ToolIndexDoc> docs = new ArrayList<>();
            for (String toolId : searchableToolIds(tenantId, null)) {
                ToolCatalogEntry entry = toolCatalogService.find(toolId).orElse(null);
                docs.add(new ToolRetrievalClient.ToolIndexDoc(
                        toolId,
                        entry != null ? entry.displayName() : null,
                        entry != null ? entry.description() : null,
                        entry != null && entry.parameters() != null ? paramsSummary(entry.parameters()) : null));
            }
            client.syncIndex(tenantId, docs).block();
            syncedFingerprint = fp;
            log.info("[ToolRetrieval] 工具索引同步完成 tenant={} tools={}", tenantId, docs.size());
        } catch (Exception e) {
            log.warn("[ToolRetrieval] 工具索引同步失败 tenant={}: {}", tenantId, e.getMessage());
        }
    }

    /** 参数摘要：参数名 + 类型 + 描述前 40 字（白名单标量，防整段 payload 进索引）。 */
    static String paramsSummary(java.util.Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        Object props = parameters.get("properties");
        if (!(props instanceof java.util.Map<?, ?> properties) || properties.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<String> required = new LinkedHashSet<>();
        Object rawRequired = parameters.get("required");
        if (rawRequired instanceof List<?> list) {
            for (Object item : list) {
                required.add(String.valueOf(item));
            }
        }
        int count = 0;
        for (Object rawKey : properties.keySet()) {
            String key = String.valueOf(rawKey);
            if (count > 0) {
                sb.append("，");
            }
            sb.append(key);
            Object value = properties.get(key);
            if (value instanceof java.util.Map<?, ?> spec && spec.get("type") != null) {
                sb.append('(').append(spec.get("type")).append(')');
            }
            if (required.contains(key)) {
                sb.append(" 必填");
            }
            if (value instanceof java.util.Map<?, ?> spec && spec.get("description") != null) {
                String desc = String.valueOf(spec.get("description")).strip();
                if (StringUtils.hasText(desc)) {
                    sb.append(' ').append(oneLine(desc));
                }
            }
            if (++count >= 6) {
                break;
            }
        }
        return sb.toString();
    }

    private static String oneLine(String text) {
        String collapsed = text.replaceAll("\\s+", " ");
        return collapsed.length() <= 60 ? collapsed : collapsed.substring(0, 60);
    }
}
