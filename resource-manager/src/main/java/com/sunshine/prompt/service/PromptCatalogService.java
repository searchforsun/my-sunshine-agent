package com.sunshine.prompt.service;

import com.sunshine.prompt.dto.PromptCatalogEntry;
import com.sunshine.prompt.dto.PromptCatalogResponse;
import com.sunshine.prompt.entity.PromptCatalogMetaEntity;
import com.sunshine.prompt.entity.PromptDefinitionEntity;
import com.sunshine.prompt.entity.PromptVersionEntity;
import com.sunshine.prompt.repo.PromptCatalogMetaRepository;
import com.sunshine.prompt.repo.PromptDefinitionRepository;
import com.sunshine.prompt.repo.PromptVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptCatalogService {
    private static final byte CATALOG_META_ID = 1;

    private final PromptDefinitionRepository definitionRepository;
    private final PromptVersionRepository versionRepository;
    private final PromptCatalogMetaRepository catalogMetaRepository;

    /**
     * 轻量版本探测：仅查 prompt_catalog_meta 单行，供 orchestrator 定期比对后决定是否拉全量。
     */
    public long catalogVersion() {
        return catalogMetaRepository.findById(CATALOG_META_ID)
                .map(PromptCatalogMetaEntity::getCatalogVersion)
                .orElse(1L);
    }

    public PromptCatalogResponse catalog() {
        long catalogVersion = catalogVersion();
        List<PromptDefinitionEntity> defs = definitionRepository.findByEnabled(true).stream()
                .sorted(Comparator.comparingInt(PromptDefinitionEntity::getPriority).reversed()
                        .thenComparing(PromptDefinitionEntity::getId))
                .toList();
        List<PromptCatalogEntry> entries = new ArrayList<>();
        for (PromptDefinitionEntity def : defs) {
            Optional<PromptVersionEntity> active = versionRepository
                    .findByPromptIdAndVersion(def.getId(), def.getActiveVersion());
            if (active.isEmpty()) {
                log.warn("[PromptCatalog] skip prompt={}: active version {} missing",
                        def.getId(), def.getActiveVersion());
                continue;
            }
            PromptVersionEntity ver = active.get();
            if (!"published".equals(ver.getStatus())) {
                log.warn("[PromptCatalog] skip prompt={}: active version {} status={}",
                        def.getId(), ver.getVersion(), ver.getStatus());
                continue;
            }
            entries.add(new PromptCatalogEntry(
                    def.getId(),
                    def.getKind(),
                    def.getDisplayName(),
                    def.isEnabled(),
                    def.getPriority(),
                    ver.getVersion(),
                    ver.getContentText(),
                    ver.getContentJson()));
        }
        return new PromptCatalogResponse(catalogVersion, List.copyOf(entries));
    }
}
