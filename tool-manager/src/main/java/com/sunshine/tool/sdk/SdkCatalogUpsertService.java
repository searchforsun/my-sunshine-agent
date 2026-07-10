package com.sunshine.tool.sdk;

import com.sunshine.common.tool.ToolIds;
import com.sunshine.common.tool.ToolConfirmationDefaults;
import com.sunshine.tool.catalog.ToolIdValidation;
import com.sunshine.tool.entity.SdkApplicationEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.repo.SdkApplicationRepository;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import com.sunshine.tool.util.SchemaHashUtil;
import com.sunshine.tools.sdk.dto.SdkToolCatalogResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SdkCatalogUpsertService {

    private final SdkApplicationRepository sdkApplicationRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final ToolSetMemberRepository toolSetMemberRepository;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    @Transactional
    public void upsert(String appId, String nacosService, SdkToolCatalogResponse catalog) {
        if (catalog == null || !StringUtils.hasText(appId)) {
            return;
        }
        Instant now = Instant.now();
        SdkApplicationEntity app = sdkApplicationRepository.findById(appId).orElseGet(SdkApplicationEntity::new);
        app.setId(appId);
        if (!StringUtils.hasText(app.getNacosService())) {
            app.setNacosService(StringUtils.hasText(nacosService) ? nacosService : appId);
        }
        if (!StringUtils.hasText(app.getDisplayName())) {
            app.setDisplayName(appId);
        }
        if (!StringUtils.hasText(app.getCatalogPath())) {
            app.setCatalogPath("/sunshine/tools/catalog");
        }
        if (!StringUtils.hasText(app.getInvokePath())) {
            app.setInvokePath("/sunshine/tools/invoke");
        }
        if (!StringUtils.hasText(app.getTenantId())) {
            app.setTenantId("default");
        }
        app.setStatus("online");
        app.setLastSeenAt(now);
        app.setSchemaVersion(catalog.schemaVersion());
        app.setUpdatedAt(now);
        if (app.getCreatedAt() == null) {
            app.setCreatedAt(now);
        }
        sdkApplicationRepository.save(app);

        if (catalog.tools() == null) {
            return;
        }
        for (SdkToolCatalogResponse.ToolEntry tool : catalog.tools()) {
            upsertTool(app, tool, now);
        }
        publish(app.getTenantId());
    }

    private void upsertTool(SdkApplicationEntity app, SdkToolCatalogResponse.ToolEntry tool, Instant now) {
        if (tool == null || !StringUtils.hasText(tool.name())) {
            return;
        }
        String catalogId = ToolIds.sdk(app.getId(), tool.name());
        String idError = ToolIdValidation.resolveIdError(app.getId(), tool.name(), catalogId);
        ToolDefinitionEntity entity = toolDefinitionRepository
                .findBySourceAndSourceRefAndExternalName("sdk", app.getId(), tool.name())
                .orElseGet(() -> toolDefinitionRepository.findById(catalogId).orElseGet(ToolDefinitionEntity::new));

        if (!StringUtils.hasText(entity.getId())) {
            entity.setId(catalogId);
            entity.setSource("sdk");
            entity.setSourceRef(app.getId());
            entity.setExternalName(tool.name());
            entity.setKind("remote");
            entity.setTenantId(app.getTenantId());
            entity.setDiscoveredAt(now);
        } else if (!catalogId.equals(entity.getId())) {
            log.warn("[SdkCatalogUpsert] 删除非法 ID 工具 {}，按 {} 重建", entity.getId(), catalogId);
            toolSetMemberRepository.deleteByToolId(entity.getId());
            toolDefinitionRepository.delete(entity);
            toolDefinitionRepository.flush();
            entity = new ToolDefinitionEntity();
            entity.setId(catalogId);
            entity.setSource("sdk");
            entity.setSourceRef(app.getId());
            entity.setExternalName(tool.name());
            entity.setKind("remote");
            entity.setTenantId(app.getTenantId());
            entity.setDiscoveredAt(now);
        }

        ToolIdValidation.apply(entity, idError);

        if (!entity.isMetadataEdited()) {
            entity.setDisplayName(tool.displayName());
            entity.setDescription(tool.description() != null ? tool.description() : "");
        }

        String newHash = SchemaHashUtil.hash(tool.parameters());
        if (entity.getSchemaHash() == null || !entity.getSchemaHash().equals(newHash)) {
            entity.setSchemaJson(tool.parameters() != null ? tool.parameters() : Map.of());
            entity.setSchemaHash(newHash);
        }

        entity.setTimelinePhase(StringUtils.hasText(tool.timelinePhase()) ? tool.timelinePhase() : "tool");
        entity.setOutputSummaryKind(StringUtils.hasText(tool.outputSummaryKind()) ? tool.outputSummaryKind() : "truncate");
        entity.setSideEffect(StringUtils.hasText(tool.sideEffect()) ? tool.sideEffect() : "read");
        if (!entity.isConfirmationEdited()) {
            entity.setRequireConfirmation(ToolConfirmationDefaults.fromSideEffect(entity.getSideEffect()));
        }
        entity.setUpdatedAt(now);
        toolDefinitionRepository.save(entity);
    }

    private void publish(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }
}
