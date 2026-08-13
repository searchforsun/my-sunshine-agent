package com.sunshine.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.agent.dto.AgentCardPreFill;
import com.sunshine.agent.dto.AgentCatalogEntry;
import com.sunshine.agent.dto.AgentCatalogIndexEntry;
import com.sunshine.agent.dto.AgentCreateRequest;
import com.sunshine.agent.dto.AgentUpdateRequest;
import com.sunshine.agent.entity.AgentDefinitionEntity;
import com.sunshine.agent.entity.AgentSkillLinkEntity;
import com.sunshine.agent.entity.AgentSkillLinkId;
import com.sunshine.agent.exception.AgentErrorCode;
import com.sunshine.agent.repo.AgentDefinitionRepository;
import com.sunshine.agent.repo.AgentSkillLinkRepository;
import com.sunshine.bizscene.exception.BizSceneErrorCode;
import com.sunshine.bizscene.service.BizSceneAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAdminService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentDefinitionRepository definitionRepository;
    private final AgentSkillLinkRepository skillLinkRepository;
    private final AgentCatalogRegistry catalogRegistry;
    private final BizSceneAdminService bizSceneAdminService;

    public List<AgentCatalogEntry> listAll() {
        return definitionRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(catalogRegistry::toEntry)
                .toList();
    }

    public List<AgentCatalogIndexEntry> listCatalogIndex(String tenantId) {
        return catalogRegistry.listEnabledIndex().stream()
                .filter(entry -> visibleTo(entry, tenantId))
                .toList();
    }

    /** 租户隔离：default 全局共享；租户私有智能体仅当前租户可见；tenantId 为空时全量（向后兼容） */
    private static boolean visibleTo(AgentCatalogIndexEntry entry, String tenantId) {
        String entryTenant = entry.tenantId();
        if (entryTenant == null || entryTenant.isBlank() || "default".equals(entryTenant)) {
            return true;
        }
        return StringUtils.hasText(tenantId) && entryTenant.equals(tenantId);
    }

    public Optional<AgentCatalogEntry> findCatalogEntry(String agentId) {
        return catalogRegistry.find(agentId);
    }

    @Transactional
    public AgentCatalogEntry create(AgentCreateRequest request) {
        if (!StringUtils.hasText(request.id()) || !StringUtils.hasText(request.displayName())) {
            throw new BizException(AgentErrorCode.ID_DISPLAY_NAME_REQUIRED);
        }
        boolean isExternal = "EXTERNAL".equalsIgnoreCase(request.source());
        if (!isExternal && !StringUtils.hasText(request.systemPrompt())) {
            throw new BizException(AgentErrorCode.SYSTEM_PROMPT_REQUIRED);
        }
        String id = request.id().strip();
        if (definitionRepository.existsById(id)) {
            throw new BizException(AgentErrorCode.AGENT_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        AgentDefinitionEntity def = new AgentDefinitionEntity();
        def.setId(id);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setSystemPrompt(request.systemPrompt() != null ? request.systemPrompt().strip() : "");
        def.setEnabled(true);
        def.setTagsJson("[]");
        def.setToolsJson(serializeToolIds(request.toolIds()));
        def.setTenantId("default");
        def.setKbScopeJson(serializeKbScope(request.kbScope()));
        def.setDataScopeJson(trimToEmpty(request.dataScopeJson(), "{}"));
        def.setPermissionsJson(trimToEmpty(request.permissionsJson(), "{}"));
        def.setModelConfigJson(trimToEmpty(request.modelConfigJson(), "{}"));
        def.setMaxIters(request.maxIters() != null && request.maxIters() > 0 ? request.maxIters() : 2);
        def.setMaxHandoffs(request.maxHandoffs() != null && request.maxHandoffs() > 0 ? request.maxHandoffs() : 5);
        if (StringUtils.hasText(request.source())) {
            def.setSource(request.source().strip());
        } else {
            def.setSource("INTERNAL");
        }
        def.setAgentCardUrl(request.agentCardUrl() != null ? request.agentCardUrl().strip() : null);
        def.setAuthConfigJson(request.authConfigJson() != null ? request.authConfigJson().strip() : null);
        def.setEndpointOverride(request.endpointOverride() != null ? request.endpointOverride().strip() : null);
        def.setKind(normalizeKind(request.kind()));
        def.setBizScene(normalizeBizScene(request.bizScene()));
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        definitionRepository.save(def);
        replaceSkillLinks(id, request.skillIds());
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public AgentCatalogEntry update(String agentId, AgentUpdateRequest request) {
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(AgentErrorCode.DISPLAY_NAME_REQUIRED);
        }
        AgentDefinitionEntity def = requireDefinition(agentId);
        boolean isExternal = "EXTERNAL".equalsIgnoreCase(def.getSource());
        if (!isExternal && !StringUtils.hasText(request.systemPrompt())) {
            throw new BizException(AgentErrorCode.SYSTEM_PROMPT_REQUIRED);
        }
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setSystemPrompt(request.systemPrompt() != null ? request.systemPrompt().strip() : "");
        def.setToolsJson(serializeToolIds(request.toolIds()));
        if (request.kbScope() != null) {
            def.setKbScopeJson(serializeKbScope(request.kbScope()));
        }
        if (StringUtils.hasText(request.dataScopeJson())) {
            def.setDataScopeJson(request.dataScopeJson().strip());
        }
        if (StringUtils.hasText(request.permissionsJson())) {
            def.setPermissionsJson(request.permissionsJson().strip());
        }
        if (StringUtils.hasText(request.modelConfigJson())) {
            def.setModelConfigJson(request.modelConfigJson().strip());
        }
        if (request.maxIters() != null && request.maxIters() > 0) {
            def.setMaxIters(request.maxIters());
        }
        if (request.maxHandoffs() != null && request.maxHandoffs() > 0) {
            def.setMaxHandoffs(request.maxHandoffs());
        }
        if (StringUtils.hasText(request.source())) {
            def.setSource(request.source().strip());
        }
        if (request.agentCardUrl() != null) {
            def.setAgentCardUrl(request.agentCardUrl().strip());
        }
        if (request.authConfigJson() != null) {
            def.setAuthConfigJson(request.authConfigJson().strip());
        }
        if (request.endpointOverride() != null) {
            def.setEndpointOverride(request.endpointOverride().strip());
        }
        def.setKind(normalizeKind(request.kind()));
        def.setBizScene(normalizeBizScene(request.bizScene()));
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        replaceSkillLinks(agentId, request.skillIds());
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public AgentCatalogEntry setEnabled(String agentId, boolean enabled) {
        AgentDefinitionEntity def = requireDefinition(agentId);
        def.setEnabled(enabled);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public void delete(String agentId) {
        String id = agentId.strip();
        if (!definitionRepository.existsById(id)) {
            throw new BizException(AgentErrorCode.AGENT_NOT_FOUND);
        }
        skillLinkRepository.deleteByIdAgentId(id);
        definitionRepository.deleteById(id);
        catalogRegistry.refresh();
        log.info("[AgentManager] deleted agent={}", id);
    }

    private AgentDefinitionEntity requireDefinition(String agentId) {
        return definitionRepository.findById(agentId.strip())
                .orElseThrow(() -> new BizException(AgentErrorCode.AGENT_NOT_FOUND));
    }

    /**
     * 拉取外部 Agent Card 并返回预填数据。
     */
    public AgentCardPreFill fetchAgentCard(String agentCardUrl) {
        if (!StringUtils.hasText(agentCardUrl)) {
            return AgentCardPreFill.error("agentCardUrl 不能为空");
        }
        String url = agentCardUrl.strip();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return AgentCardPreFill.error("agentCardUrl 必须以 http:// 或 https:// 开头");
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return AgentCardPreFill.error("Agent Card 返回 HTTP " + resp.statusCode());
            }
            String body = resp.body();
            if (!StringUtils.hasText(body)) {
                return AgentCardPreFill.error("Agent Card 返回内容为空");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> card = MAPPER.readValue(body, Map.class);

            String name = (String) card.getOrDefault("name", "");
            String description = (String) card.getOrDefault("description", "");
            String version = (String) card.getOrDefault("version", "");

            List<String> skillNames = new ArrayList<>();
            Object skills = card.get("skills");
            if (skills instanceof List) {
                for (Object sk : (List<?>) skills) {
                    if (sk instanceof Map) {
                        Object skName = ((Map<?, ?>) sk).get("name");
                        if (skName != null) {
                            skillNames.add(String.valueOf(skName));
                        }
                    }
                }
            }

            String endpointUrl = "";
            @SuppressWarnings("unchecked")
            List<Object> interfaces = (List<Object>) card.get("supportedInterfaces");
            if (interfaces != null && !interfaces.isEmpty() && interfaces.get(0) instanceof Map) {
                Object urlVal = ((Map<?, ?>) interfaces.get(0)).get("url");
                if (urlVal != null) {
                    endpointUrl = String.valueOf(urlVal);
                }
            }

            return new AgentCardPreFill(name, description, version, skillNames, endpointUrl, null);
        } catch (Exception e) {
            log.warn("[AgentAdminService] fetch agent card failed: url={} error={}", url, e.getMessage());
            return AgentCardPreFill.error("拉取 Agent Card 失败: " + (e.getMessage() != null ? e.getMessage() : "network error"));
        }
    }

    private String serializeToolIds(List<String> toolIds) {
        try {
            List<String> clean = new ArrayList<>();
            if (toolIds != null) {
                for (String id : toolIds) {
                    if (StringUtils.hasText(id) && !"*".equals(id.strip())) {
                        clean.add(id.strip());
                    }
                }
            }
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String serializeKbScope(List<String> kbScope) {
        if (kbScope == null || kbScope.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(kbScope);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String trimToEmpty(String value, String fallback) {
        if (StringUtils.hasText(value)) {
            return value.strip();
        }
        return fallback;
    }

    /** 会话形态 kind 收敛：chat|task|all；空/非法回落 all（Lab/校验见 K2） */
    private static String normalizeKind(String kind) {
        if (kind == null) {
            return "all";
        }
        String v = kind.strip();
        return switch (v) {
            case "chat", "task", "all" -> v;
            default -> "all";
        };
    }

    /** 业务场景码：空→null；非空必须在业务场景 Lab 且 active，否则拒绝保存（K2） */
    private String normalizeBizScene(String bizScene) {
        if (!StringUtils.hasText(bizScene)) {
            return null;
        }
        String v = bizScene.strip();
        if (!bizSceneAdminService.isActiveBizScene(v)) {
            throw new BizException(BizSceneErrorCode.SCENE_NOT_ACTIVE);
        }
        return v;
    }

    private void replaceSkillLinks(String agentId, List<String> skillIds) {
        skillLinkRepository.deleteByIdAgentId(agentId);
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }
        List<AgentSkillLinkEntity> links = new ArrayList<>();
        for (String raw : skillIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            AgentSkillLinkEntity link = new AgentSkillLinkEntity();
            AgentSkillLinkId id = new AgentSkillLinkId();
            id.setAgentId(agentId);
            id.setSkillId(raw.strip());
            link.setId(id);
            links.add(link);
        }
        skillLinkRepository.saveAll(links);
    }
}
