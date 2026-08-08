package com.sunshine.tool.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.tool.admin.McpServerPatchRequest;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.mcp.McpImportService;
import com.sunshine.tool.repo.McpServerRepository;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class McpServerAdminService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerRepository mcpServerRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final ToolSetMemberRepository toolSetMemberRepository;
    private final McpImportService mcpImportService;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    public List<McpServerEntity> listAll() {
        return mcpServerRepository.findAll();
    }

    @Transactional
    public McpServerEntity create(McpServerEntity request) {
        if (!StringUtils.hasText(request.getId())) {
            throw new BizException(ToolErrorCode.MCP_SERVER_ID_REQUIRED);
        }
        if (mcpServerRepository.existsById(request.getId())) {
            throw new BizException(ToolErrorCode.MCP_SERVER_EXISTS);
        }
        Instant now = Instant.now();
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (!StringUtils.hasText(request.getTenantId())) {
            request.setTenantId("default");
        }
        McpServerEntity saved = mcpServerRepository.save(request);
        publish(saved.getTenantId());
        return saved;
    }

    @Transactional
    public List<McpServerEntity> importJson(String rawJson) {
        List<McpServerEntity> parsed = mcpImportService.parse(rawJson);
        Instant now = Instant.now();
        for (McpServerEntity server : parsed) {
            McpServerEntity existing = mcpServerRepository.findById(server.getId()).orElse(null);
            if (existing != null) {
                server.setCreatedAt(existing.getCreatedAt());
                server.setEnabled(existing.isEnabled());
                server.setDisplayName(StringUtils.hasText(existing.getDisplayName())
                        ? existing.getDisplayName() : server.getDisplayName());
            } else {
                server.setCreatedAt(now);
                server.setEnabled(false);
            }
            server.setUpdatedAt(now);
            mcpServerRepository.save(server);
            publish(server.getTenantId());
        }
        return parsed;
    }

    public String exportJson() {
        return mcpImportService.export(mcpServerRepository.findAll());
    }

    @Transactional
    public McpServerEntity update(String id, McpServerPatchRequest request) {
        McpServerEntity server = mcpServerRepository.findById(id)
                .orElseThrow(() -> new BizException(ToolErrorCode.MCP_SERVER_NOT_FOUND));
        if (StringUtils.hasText(request.displayName())) {
            server.setDisplayName(request.displayName());
        }
        if (StringUtils.hasText(request.transport())) {
            server.setTransport(request.transport().trim());
        }
        if (request.command() != null) {
            server.setCommand(request.command());
        }
        if (request.argsJson() != null) {
            server.setArgsJson(request.argsJson());
        }
        if (request.endpoint() != null) {
            server.setEndpoint(request.endpoint());
        }
        if (request.envJson() != null) {
            server.setEnvJson(request.envJson());
        }
        if (request.enabled() != null) {
            server.setEnabled(request.enabled());
        }
        server.setUpdatedAt(Instant.now());
        McpServerEntity saved = mcpServerRepository.save(server);
        publish(saved.getTenantId());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        McpServerEntity server = mcpServerRepository.findById(id)
                .orElseThrow(() -> new BizException(ToolErrorCode.MCP_SERVER_NOT_FOUND));
        List<ToolDefinitionEntity> tools = toolDefinitionRepository.findBySourceAndSourceRef("mcp", id);
        for (ToolDefinitionEntity tool : tools) {
            toolSetMemberRepository.deleteByToolId(tool.getId());
            toolDefinitionRepository.delete(tool);
        }
        mcpServerRepository.delete(server);
        publish(server.getTenantId());
    }

    private void publish(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }
}
