package com.sunshine.model.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.model.dto.ModelRouteResponse;
import com.sunshine.model.dto.ModelRouteUpsertRequest;
import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.entity.ModelRoutePolicyEntity;
import com.sunshine.model.event.ModelCatalogChangePublisher;
import com.sunshine.model.exception.ModelErrorCode;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelRoutePolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRoutePolicyServiceTest {

    @Mock
    private ModelRoutePolicyRepository routeRepository;
    @Mock
    private ModelDefinitionRepository definitionRepository;
    @Mock
    private ModelCatalogChangePublisher catalogChangePublisher;

    private ModelRoutePolicyService service;

    @BeforeEach
    void setUp() {
        service = new ModelRoutePolicyService(routeRepository, definitionRepository, catalogChangePublisher);
    }

    private static ModelDefinitionEntity enabledDef(String name) {
        ModelDefinitionEntity def = new ModelDefinitionEntity();
        def.setModelName(name);
        def.setEnabled(true);
        return def;
    }

    @Test
    void upsert_unknownCallSite_throwsInvalid() {
        ModelRouteUpsertRequest req = new ModelRouteUpsertRequest(
                "custom-site", List.of("qwen-plus"), "first-available", true, "default", null);
        assertThatThrownBy(() -> service.upsertOne(req))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode().getKey())
                        .isEqualTo(ModelErrorCode.ROUTE_CALL_SITE_INVALID.getKey()));
        verify(routeRepository, never()).save(any());
        verify(catalogChangePublisher, never()).publish(any());
    }

    @Test
    void upsert_emptyModels_throwsRequired() {
        ModelRouteUpsertRequest req = new ModelRouteUpsertRequest(
                "rewrite", List.of(), "first-available", true, "default", null);
        assertThatThrownBy(() -> service.upsertOne(req))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode().getKey())
                        .isEqualTo(ModelErrorCode.ROUTE_MODELS_REQUIRED.getKey()));
    }

    @Test
    void upsert_unsupportedStrategy_throwsInvalid() {
        ModelRouteUpsertRequest req = new ModelRouteUpsertRequest(
                "rewrite", List.of("qwen-plus"), "weighted", true, "default", null);
        assertThatThrownBy(() -> service.upsertOne(req))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode().getKey())
                        .isEqualTo(ModelErrorCode.ROUTE_STRATEGY_INVALID.getKey()));
    }

    @Test
    void upsert_modelNotInRegistry_throwsNotFound() {
        when(definitionRepository.findByTenantIdAndModelName("default", "nope"))
                .thenReturn(Optional.empty());
        ModelRouteUpsertRequest req = new ModelRouteUpsertRequest(
                "rewrite", List.of("nope"), "first-available", true, "default", null);
        assertThatThrownBy(() -> service.upsertOne(req))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode().getKey())
                        .isEqualTo(ModelErrorCode.MODEL_NOT_FOUND.getKey()));
        verify(routeRepository, never()).save(any());
    }

    @Test
    void upsert_valid_persistsDedupedModelsAndPublishes() {
        when(definitionRepository.findByTenantIdAndModelName(eq("default"), any()))
                .thenReturn(Optional.of(enabledDef("qwen-plus")));
        when(routeRepository.findByTenantIdAndCallSite("default", "rewrite"))
                .thenReturn(Optional.empty());
        when(routeRepository.save(any(ModelRoutePolicyEntity.class))).thenAnswer(inv -> {
            ModelRoutePolicyEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        ModelRouteUpsertRequest req = new ModelRouteUpsertRequest(
                "rewrite", List.of("qwen-plus", "qwen-plus", " deepseek-v4-flash "),
                "first-available", true, "default", null);
        ModelRouteResponse resp = service.upsertOne(req);

        assertThat(resp.callSite()).isEqualTo("rewrite");
        assertThat(resp.models()).containsExactly("qwen-plus", "deepseek-v4-flash");
        assertThat(resp.strategy()).isEqualTo("first-available");
        ArgumentCaptor<ModelRoutePolicyEntity> captor = ArgumentCaptor.forClass(ModelRoutePolicyEntity.class);
        verify(routeRepository).save(captor.capture());
        assertThat(captor.getValue().getModels()).contains("deepseek-v4-flash");
        verify(catalogChangePublisher).publish(eq("default"));
    }

    @Test
    void readModels_invalidJson_returnsEmpty() {
        assertThat(ModelRoutePolicyService.readModels("not-json")).isEmpty();
        assertThat(ModelRoutePolicyService.readModels("[\"a\",\"b\"]")).containsExactly("a", "b");
    }
}
