package com.sunshine.model.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.model.domain.ModelSceneKey;
import com.sunshine.model.dto.ModelSceneKeyMeta;
import com.sunshine.model.dto.ModelSceneResponse;
import com.sunshine.model.dto.ModelSceneUpsertRequest;
import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.entity.ModelSceneBindingEntity;
import com.sunshine.model.event.ModelCatalogChangePublisher;
import com.sunshine.model.exception.ModelErrorCode;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelSceneBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelSceneService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelSceneBindingRepository sceneRepository;
    private final ModelDefinitionRepository definitionRepository;
    private final ModelCatalogChangePublisher catalogChangePublisher;

    public List<ModelSceneKeyMeta> listKeys() {
        return Arrays.stream(ModelSceneKey.values())
                .map(k -> new ModelSceneKeyMeta(k.key(), k.label(), k.description()))
                .toList();
    }

    public List<ModelSceneResponse> list(String tenantId) {
        String tid = ModelJsonSupport.normalizeTenantId(tenantId);
        return sceneRepository.findByTenantIdOrderBySceneKeyAsc(tid).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<ModelSceneResponse> upsert(JsonNode body) {
        List<ModelSceneUpsertRequest> requests = parseUpsertBody(body);
        if (requests.isEmpty()) {
            throw new BizException(ModelErrorCode.SCENE_REQUEST_INVALID);
        }
        List<ModelSceneResponse> results = new ArrayList<>(requests.size());
        String publishTenant = null;
        for (ModelSceneUpsertRequest request : requests) {
            ModelSceneResponse saved = upsertOne(request);
            results.add(saved);
            publishTenant = saved.tenantId();
        }
        if (publishTenant != null) {
            catalogChangePublisher.publish(publishTenant);
        }
        return results;
    }

    private ModelSceneResponse upsertOne(ModelSceneUpsertRequest request) {
        if (!StringUtils.hasText(request.sceneKey())) {
            throw new BizException(ModelErrorCode.SCENE_KEY_REQUIRED);
        }
        if (!StringUtils.hasText(request.primaryModel())) {
            throw new BizException(ModelErrorCode.SCENE_PRIMARY_REQUIRED);
        }
        ModelSceneKey scene = ModelSceneKey.fromKey(request.sceneKey())
                .orElseThrow(() -> new BizException(ModelErrorCode.SCENE_KEY_INVALID));
        String tid = ModelJsonSupport.normalizeTenantId(request.tenantId());
        String sceneKey = scene.key();
        String primary = request.primaryModel().strip();
        String fallback = StringUtils.hasText(request.fallbackModel()) ? request.fallbackModel().strip() : null;
        requireEnabledModel(tid, primary);
        if (fallback != null) {
            requireEnabledModel(tid, fallback);
        }
        Instant now = Instant.now();
        ModelSceneBindingEntity entity = sceneRepository.findByTenantIdAndSceneKey(tid, sceneKey)
                .orElseGet(ModelSceneBindingEntity::new);
        boolean creating = entity.getId() == null;
        entity.setSceneKey(sceneKey);
        entity.setPrimaryModel(primary);
        entity.setFallbackModel(fallback);
        entity.setExtras(ModelJsonSupport.writeExtras(request.extras()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setTenantId(tid);
        if (StringUtils.hasText(request.remark())) {
            entity.setRemark(request.remark().strip());
        } else if (creating || !StringUtils.hasText(entity.getRemark())) {
            entity.setRemark(scene.description());
        }
        if (creating) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        sceneRepository.save(entity);
        return toResponse(entity);
    }

    private void requireEnabledModel(String tenantId, String modelName) {
        ModelDefinitionEntity def = definitionRepository.findByTenantIdAndModelName(tenantId, modelName)
                .orElseThrow(() -> new BizException(ModelErrorCode.MODEL_NOT_FOUND));
        if (!def.isEnabled()) {
            throw new BizException(ModelErrorCode.MODEL_NOT_ENABLED);
        }
    }

    private List<ModelSceneUpsertRequest> parseUpsertBody(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new BizException(ModelErrorCode.SCENE_REQUEST_INVALID);
        }
        try {
            if (body.isArray()) {
                List<ModelSceneUpsertRequest> list = new ArrayList<>();
                for (JsonNode node : body) {
                    list.add(MAPPER.convertValue(node, ModelSceneUpsertRequest.class));
                }
                return list;
            }
            return List.of(MAPPER.convertValue(body, ModelSceneUpsertRequest.class));
        } catch (IllegalArgumentException e) {
            throw new BizException(ModelErrorCode.SCENE_REQUEST_INVALID);
        }
    }

    private ModelSceneResponse toResponse(ModelSceneBindingEntity entity) {
        ModelSceneKey key = ModelSceneKey.fromKey(entity.getSceneKey()).orElse(null);
        return new ModelSceneResponse(
                entity.getId(),
                entity.getSceneKey(),
                key != null ? key.label() : entity.getSceneKey(),
                key != null ? key.description() : (entity.getRemark() != null ? entity.getRemark() : ""),
                entity.getPrimaryModel(),
                entity.getFallbackModel(),
                ModelJsonSupport.readExtras(entity.getExtras()),
                entity.isEnabled(),
                entity.getTenantId(),
                entity.getRemark(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
