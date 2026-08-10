package com.sunshine.model.service;

import com.sunshine.model.dto.ModelCatalogDefinition;
import com.sunshine.model.dto.ModelCatalogProvider;
import com.sunshine.model.dto.ModelCatalogResponse;
import com.sunshine.model.dto.ModelCatalogScene;
import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.entity.ModelProviderEntity;
import com.sunshine.model.entity.ModelSceneBindingEntity;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelProviderRepository;
import com.sunshine.model.repo.ModelSceneBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelCatalogService {
    private final ModelProviderRepository providerRepository;
    private final ModelDefinitionRepository definitionRepository;
    private final ModelSceneBindingRepository sceneRepository;

    public ModelCatalogResponse publicCatalog(String tenantId) {
        return buildCatalog(tenantId, false);
    }

    public ModelCatalogResponse gatewayCatalog(String tenantId) {
        return buildCatalog(tenantId, true);
    }

    private ModelCatalogResponse buildCatalog(String tenantId, boolean includeApiKeyEnc) {
        String tid = ModelJsonSupport.normalizeTenantId(tenantId);
        List<ModelCatalogProvider> providers = providerRepository.findByTenantIdOrderByProviderKeyAsc(tid).stream()
                .map(p -> toProvider(p, includeApiKeyEnc))
                .toList();
        List<ModelCatalogDefinition> definitions = definitionRepository
                .findByTenantIdOrderBySortOrderAscModelNameAsc(tid).stream()
                .map(this::toDefinition)
                .toList();
        List<ModelCatalogScene> scenes = sceneRepository.findByTenantIdOrderBySceneKeyAsc(tid).stream()
                .map(this::toScene)
                .toList();
        return new ModelCatalogResponse(providers, definitions, scenes);
    }

    private ModelCatalogProvider toProvider(ModelProviderEntity entity, boolean includeApiKeyEnc) {
        return new ModelCatalogProvider(
                entity.getProviderKey(),
                entity.getDisplayName(),
                entity.getProtocol(),
                entity.getBaseUrl(),
                entity.getPathPrefix(),
                entity.isEnabled(),
                includeApiKeyEnc ? entity.getApiKeyEnc() : null
        );
    }

    private ModelCatalogDefinition toDefinition(ModelDefinitionEntity entity) {
        return new ModelCatalogDefinition(
                entity.getModelName(),
                entity.getProviderKey(),
                entity.getDisplayName(),
                entity.getContextWindow(),
                entity.getMaxOutputTokens(),
                entity.getEncoding(),
                ModelJsonSupport.readCapabilities(entity.getCapabilities()),
                ModelJsonSupport.readExtras(entity.getRequestExtras()),
                entity.isUserSelectable(),
                entity.isEnabled(),
                entity.getSortOrder()
        );
    }

    private ModelCatalogScene toScene(ModelSceneBindingEntity entity) {
        return new ModelCatalogScene(
                entity.getSceneKey(),
                entity.getPrimaryModel(),
                entity.getFallbackModel(),
                ModelJsonSupport.readExtras(entity.getExtras()),
                entity.isEnabled()
        );
    }
}
