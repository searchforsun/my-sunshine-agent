package com.sunshine.tool.service;

import com.sunshine.tool.dto.ToolCatalogEntry;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DbToolCatalogService {

    private final ToolDefinitionRepository toolDefinitionRepository;

    @Transactional(readOnly = true)
    public List<ToolCatalogEntry> listCatalog(String tenantId, boolean enabledOnly) {
        String effectiveTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        return toolDefinitionRepository.findVisibleForTenant(effectiveTenant, enabledOnly).stream()
                .map(this::toEntry)
                .toList();
    }

    private ToolCatalogEntry toEntry(ToolDefinitionEntity entity) {
        Map<String, Object> parameters = entity.getSchemaJson() != null
                ? entity.getSchemaJson()
                : Map.of("type", "object", "properties", Map.of());
        return new ToolCatalogEntry(
                entity.getId(),
                entity.getDisplayName(),
                entity.getDescription() != null ? entity.getDescription() : "",
                entity.getKind(),
                entity.getTimelineSummaryTemplate(),
                entity.getTimelineSummaryExtract(),
                parameters,
                entity.getSideEffect(),
                entity.isRequireConfirmation(),
                entity.isEnabled(),
                entity.isIdValid(),
                entity.getIdError());
    }
}
