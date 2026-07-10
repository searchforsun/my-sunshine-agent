package com.sunshine.tool.mcp;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.catalog.ToolIdValidation;
import com.sunshine.tool.config.ToolIntegrationProperties;
import com.sunshine.common.tool.ToolConfirmationDefaults;
import com.sunshine.common.tool.ToolIds;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.McpServerRepository;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import com.sunshine.tool.util.SchemaHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP probe 同步：tools/list → upsert tool_definition */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpSyncService {

    private final McpServerRepository mcpServerRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final ToolSetMemberRepository toolSetMemberRepository;
    private final McpJsonRpcClient mcpJsonRpcClient;
    private final ToolIntegrationProperties properties;
    private final McpProbeRecorder mcpProbeRecorder;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    @Scheduled(fixedDelayString = "${tool.mcp.refresh-interval-seconds:300}000")
    public void scheduledRefresh() {
        mcpServerRepository.findByEnabledTrue().forEach(server -> {
            try {
                probe(server.getId());
            } catch (Exception e) {
                log.warn("[McpSyncService] scheduled probe failed server={}: {}", server.getId(), e.getMessage());
            }
        });
    }

    @Transactional
    public void probe(String serverId) {
        McpServerEntity server = mcpServerRepository.findById(serverId)
                .orElseThrow(() -> new BizException(ToolErrorCode.MCP_SERVER_NOT_FOUND));
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.getMcp().getInvokeTimeoutSeconds()));
        Instant now = Instant.now();
        String probeStatus = "ok";
        String probeError = null;
        try {
            List<McpJsonRpcClient.McpToolDescriptor> tools = mcpJsonRpcClient.listTools(server, timeout);
            Set<String> seen = new HashSet<>();
            for (McpJsonRpcClient.McpToolDescriptor tool : tools) {
                upsertTool(server, tool, now);
                seen.add(tool.name());
            }
            pruneMissingTools(server, seen);
            log.info("[McpSyncService] probe ok server={} tools={}", serverId, tools.size());
        } catch (Exception e) {
            probeStatus = "error";
            probeError = truncate(e.getMessage(), 512);
            log.warn("[McpSyncService] probe failed server={}: {}", serverId, e.getMessage());
            mcpProbeRecorder.record(serverId, now, probeStatus, probeError);
            publishCatalogChange(server.getTenantId());
            throw new BizException(ToolErrorCode.MCP_PROBE_FAILED);
        }
        mcpProbeRecorder.record(serverId, now, probeStatus, probeError);
        publishCatalogChange(server.getTenantId());
    }

    private void upsertTool(McpServerEntity server, McpJsonRpcClient.McpToolDescriptor tool, Instant now) {
        String toolId = ToolIds.mcp(server.getId(), tool.name());
        String idError = ToolIdValidation.resolveIdError(server.getId(), tool.name(), toolId);
        ToolDefinitionEntity entity = toolDefinitionRepository
                .findBySourceAndSourceRefAndExternalName("mcp", server.getId(), tool.name())
                .orElseGet(ToolDefinitionEntity::new);
        Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
        String newHash = SchemaHashUtil.hash(schema);

        if (!StringUtils.hasText(entity.getId())) {
            entity.setId(toolId);
            entity.setSource("mcp");
            entity.setSourceRef(server.getId());
            entity.setExternalName(tool.name());
            entity.setKind("mcp");
            entity.setTenantId(server.getTenantId());
            entity.setDiscoveredAt(now);
            entity.setEnabled(false);
        } else if (!toolId.equals(entity.getId())) {
            log.warn("[McpSyncService] 删除非法 ID 工具 {}，按 {} 重建", entity.getId(), toolId);
            toolSetMemberRepository.deleteByToolId(entity.getId());
            toolDefinitionRepository.delete(entity);
            toolDefinitionRepository.flush();
            entity = new ToolDefinitionEntity();
            entity.setId(toolId);
            entity.setSource("mcp");
            entity.setSourceRef(server.getId());
            entity.setExternalName(tool.name());
            entity.setKind("mcp");
            entity.setTenantId(server.getTenantId());
            entity.setDiscoveredAt(now);
            entity.setEnabled(false);
        }

        ToolIdValidation.apply(entity, idError);

        if (!entity.isMetadataEdited()) {
            entity.setDisplayName(StringUtils.hasText(tool.description()) ? tool.description() : tool.name());
            entity.setDescription(tool.description() != null ? tool.description() : "");
        }

        entity.setSchemaJson(schema);
        entity.setSchemaHash(newHash);
        entity.setTimelinePhase("tool");
        entity.setOutputSummaryKind("truncate");
        entity.setSideEffect("read");
        if (!entity.isConfirmationEdited()) {
            entity.setRequireConfirmation(ToolConfirmationDefaults.fromSideEffect(entity.getSideEffect()));
        }
        entity.setUpdatedAt(now);
        toolDefinitionRepository.save(entity);
    }

    private void pruneMissingTools(McpServerEntity server, Set<String> seenNames) {
        List<ToolDefinitionEntity> existing = toolDefinitionRepository.findBySourceAndSourceRef("mcp", server.getId());
        for (ToolDefinitionEntity entity : existing) {
            if (!seenNames.contains(entity.getExternalName())) {
                toolDefinitionRepository.delete(entity);
            }
        }
    }

    private void publishCatalogChange(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }

    private String truncate(String message, int maxLen) {
        if (message == null) {
            return null;
        }
        return message.length() <= maxLen ? message : message.substring(0, maxLen);
    }
}
