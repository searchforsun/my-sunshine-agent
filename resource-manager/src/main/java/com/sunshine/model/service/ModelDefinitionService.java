package com.sunshine.model.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.model.dto.ModelDefinitionRequest;
import com.sunshine.model.dto.ModelDefinitionResponse;
import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.event.ModelCatalogChangePublisher;
import com.sunshine.model.exception.ModelErrorCode;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelDefinitionService {
    private final ModelDefinitionRepository definitionRepository;
    private final ModelProviderRepository providerRepository;
    private final ModelCatalogChangePublisher catalogChangePublisher;

    public List<ModelDefinitionResponse> list(String tenantId) {
        String tid = ModelJsonSupport.normalizeTenantId(tenantId);
        return definitionRepository.findByTenantIdOrderBySortOrderAscModelNameAsc(tid).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ModelDefinitionResponse create(ModelDefinitionRequest request) {
        validateRequired(request);
        String tid = ModelJsonSupport.normalizeTenantId(request.tenantId());
        String modelName = request.modelName().strip();
        String providerKey = request.providerKey().strip();
        if (definitionRepository.existsByTenantIdAndModelName(tid, modelName)) {
            throw new BizException(ModelErrorCode.MODEL_ALREADY_EXISTS);
        }
        if (!providerRepository.existsByTenantIdAndProviderKey(tid, providerKey)) {
            throw new BizException(ModelErrorCode.PROVIDER_NOT_FOUND);
        }
        Instant now = Instant.now();
        ModelDefinitionEntity entity = new ModelDefinitionEntity();
        entity.setProviderKey(providerKey);
        entity.setModelName(modelName);
        entity.setDisplayName(request.displayName().strip());
        entity.setContextWindow(request.contextWindow() != null && request.contextWindow() > 0
                ? request.contextWindow() : 32768);
        entity.setMaxOutputTokens(request.maxOutputTokens() != null && request.maxOutputTokens() > 0
                ? request.maxOutputTokens() : 8192);
        entity.setEncoding(StringUtils.hasText(request.encoding()) ? request.encoding().strip() : "cl100k_base");
        entity.setCapabilities(ModelJsonSupport.writeCapabilities(request.capabilities()));
        entity.setRequestExtras(ModelJsonSupport.writeExtras(request.requestExtras()));
        entity.setUserSelectable(request.userSelectable() != null && request.userSelectable());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        entity.setTenantId(tid);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        definitionRepository.save(entity);
        catalogChangePublisher.publish(tid);
        return toResponse(entity);
    }

    @Transactional
    public ModelDefinitionResponse update(Long id, ModelDefinitionRequest request) {
        ModelDefinitionEntity entity = require(id);
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(ModelErrorCode.MODEL_DISPLAY_NAME_REQUIRED);
        }
        if (StringUtils.hasText(request.providerKey())) {
            String providerKey = request.providerKey().strip();
            if (!providerRepository.existsByTenantIdAndProviderKey(entity.getTenantId(), providerKey)) {
                throw new BizException(ModelErrorCode.PROVIDER_NOT_FOUND);
            }
            entity.setProviderKey(providerKey);
        }
        if (StringUtils.hasText(request.modelName())) {
            String modelName = request.modelName().strip();
            if (!modelName.equals(entity.getModelName())
                    && definitionRepository.existsByTenantIdAndModelName(entity.getTenantId(), modelName)) {
                throw new BizException(ModelErrorCode.MODEL_ALREADY_EXISTS);
            }
            entity.setModelName(modelName);
        }
        entity.setDisplayName(request.displayName().strip());
        if (request.contextWindow() != null && request.contextWindow() > 0) {
            entity.setContextWindow(request.contextWindow());
        }
        if (request.maxOutputTokens() != null && request.maxOutputTokens() > 0) {
            entity.setMaxOutputTokens(request.maxOutputTokens());
        }
        if (StringUtils.hasText(request.encoding())) {
            entity.setEncoding(request.encoding().strip());
        }
        if (request.capabilities() != null) {
            entity.setCapabilities(ModelJsonSupport.writeCapabilities(request.capabilities()));
        }
        // 显式传入（含 null/空）即覆盖，便于清空请求参数
        entity.setRequestExtras(ModelJsonSupport.writeExtras(request.requestExtras()));
        if (request.userSelectable() != null) {
            entity.setUserSelectable(request.userSelectable());
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(request.sortOrder());
        }
        entity.setUpdatedAt(Instant.now());
        definitionRepository.save(entity);
        catalogChangePublisher.publish(entity.getTenantId());
        return toResponse(entity);
    }

    @Transactional
    public ModelDefinitionResponse toggle(Long id) {
        ModelDefinitionEntity entity = require(id);
        entity.setEnabled(!entity.isEnabled());
        entity.setUpdatedAt(Instant.now());
        definitionRepository.save(entity);
        catalogChangePublisher.publish(entity.getTenantId());
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long id) {
        ModelDefinitionEntity entity = require(id);
        String tid = entity.getTenantId();
        definitionRepository.delete(entity);
        catalogChangePublisher.publish(tid);
    }

    private void validateRequired(ModelDefinitionRequest request) {
        if (!StringUtils.hasText(request.providerKey())) {
            throw new BizException(ModelErrorCode.MODEL_PROVIDER_REQUIRED);
        }
        if (!StringUtils.hasText(request.modelName())) {
            throw new BizException(ModelErrorCode.MODEL_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(ModelErrorCode.MODEL_DISPLAY_NAME_REQUIRED);
        }
    }

    private ModelDefinitionEntity require(Long id) {
        return definitionRepository.findById(id)
                .orElseThrow(() -> new BizException(ModelErrorCode.MODEL_NOT_FOUND));
    }

    private ModelDefinitionResponse toResponse(ModelDefinitionEntity entity) {
        return new ModelDefinitionResponse(
                entity.getId(),
                entity.getProviderKey(),
                entity.getModelName(),
                entity.getDisplayName(),
                entity.getContextWindow(),
                entity.getMaxOutputTokens(),
                entity.getEncoding(),
                ModelJsonSupport.readCapabilities(entity.getCapabilities()),
                ModelJsonSupport.readExtras(entity.getRequestExtras()),
                entity.isUserSelectable(),
                entity.isEnabled(),
                entity.getSortOrder(),
                entity.getTenantId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}