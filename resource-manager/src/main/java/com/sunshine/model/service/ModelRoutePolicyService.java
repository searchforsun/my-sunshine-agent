package com.sunshine.model.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.model.CallSiteKey;
import com.sunshine.model.dto.ModelRouteResponse;
import com.sunshine.model.dto.ModelRouteUpsertRequest;
import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.entity.ModelRoutePolicyEntity;
import com.sunshine.model.event.ModelCatalogChangePublisher;
import com.sunshine.model.exception.ModelErrorCode;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelRoutePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRoutePolicyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STRATEGY_FIRST_AVAILABLE = "first-available";

    private final ModelRoutePolicyRepository routeRepository;
    private final ModelDefinitionRepository definitionRepository;
    private final ModelCatalogChangePublisher catalogChangePublisher;

    public List<ModelRouteResponse> list(String tenantId) {
        String tid = ModelJsonSupport.normalizeTenantId(tenantId);
        return routeRepository.findByTenantIdOrderByCallSiteAsc(tid).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ModelRouteResponse upsertOne(ModelRouteUpsertRequest request) {
        if (!StringUtils.hasText(request.callSite())) {
            throw new BizException(ModelErrorCode.ROUTE_CALL_SITE_REQUIRED);
        }
        CallSiteKey callSite = CallSiteKey.fromKey(request.callSite())
                .orElseThrow(() -> new BizException(ModelErrorCode.ROUTE_CALL_SITE_INVALID));
        if (request.models() == null || request.models().isEmpty()) {
            throw new BizException(ModelErrorCode.ROUTE_MODELS_REQUIRED);
        }
        String tid = ModelJsonSupport.normalizeTenantId(request.tenantId());
        String strategy = StringUtils.hasText(request.strategy()) ? request.strategy().strip() : STRATEGY_FIRST_AVAILABLE;
        if (!STRATEGY_FIRST_AVAILABLE.equals(strategy)) {
            throw new BizException(ModelErrorCode.ROUTE_STRATEGY_INVALID);
        }
        List<String> models = request.models().stream()
                .filter(StringUtils::hasText)
                .map(String::strip)
                .distinct()
                .toList();
        if (models.isEmpty()) {
            throw new BizException(ModelErrorCode.ROUTE_MODELS_REQUIRED);
        }
        for (String model : models) {
            requireEnabledModel(tid, model);
        }
        Instant now = Instant.now();
        ModelRoutePolicyEntity entity = routeRepository.findByTenantIdAndCallSite(tid, callSite.key())
                .orElseGet(ModelRoutePolicyEntity::new);
        boolean creating = entity.getId() == null;
        entity.setCallSite(callSite.key());
        entity.setModels(writeModels(models));
        entity.setStrategy(strategy);
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setTenantId(tid);
        if (StringUtils.hasText(request.remark())) {
            entity.setRemark(request.remark().strip());
        } else if (creating) {
            entity.setRemark(callSite.description());
        }
        if (creating) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        routeRepository.save(entity);
        catalogChangePublisher.publish(tid);
        log.info("[ModelRoutePolicy] upsert callSite={} models={} strategy={}",
                callSite.key(), models, strategy);
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long id) {
        ModelRoutePolicyEntity entity = routeRepository.findById(id)
                .orElseThrow(() -> new BizException(ModelErrorCode.ROUTE_NOT_FOUND));
        routeRepository.delete(entity);
        catalogChangePublisher.publish(entity.getTenantId());
    }

    private void requireEnabledModel(String tenantId, String modelName) {
        ModelDefinitionEntity def = definitionRepository.findByTenantIdAndModelName(tenantId, modelName)
                .orElseThrow(() -> new BizException(ModelErrorCode.MODEL_NOT_FOUND));
        if (!def.isEnabled()) {
            throw new BizException(ModelErrorCode.MODEL_NOT_ENABLED);
        }
    }

    static String writeModels(List<String> models) {
        try {
            return MAPPER.writeValueAsString(models);
        } catch (Exception e) {
            throw new IllegalStateException("模型池序列化失败", e);
        }
    }

    static List<String> readModels(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[ModelRoutePolicy] 模型池 JSON 解析失败，按空池处理: {}", e.getMessage());
            return List.of();
        }
    }

    public static Optional<List<String>> readModelsQuietly(String json) {
        List<String> models = readModels(json);
        return models.isEmpty() ? Optional.empty() : Optional.of(models);
    }

    private ModelRouteResponse toResponse(ModelRoutePolicyEntity entity) {
        CallSiteKey key = CallSiteKey.fromKey(entity.getCallSite()).orElse(null);
        return new ModelRouteResponse(
                entity.getId(),
                entity.getCallSite(),
                key != null ? key.label() : entity.getCallSite(),
                key != null ? key.description() : (entity.getRemark() != null ? entity.getRemark() : ""),
                new ArrayList<>(readModels(entity.getModels())),
                entity.getStrategy(),
                entity.isEnabled(),
                entity.getTenantId(),
                entity.getRemark(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
