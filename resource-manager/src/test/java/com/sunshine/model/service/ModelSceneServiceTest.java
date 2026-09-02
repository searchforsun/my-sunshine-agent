package com.sunshine.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.model.dto.ModelSceneKeyMeta;
import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.entity.ModelSceneBindingEntity;
import com.sunshine.model.event.ModelCatalogChangePublisher;
import com.sunshine.model.exception.ModelErrorCode;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelSceneBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelSceneServiceTest {

    @Mock
    private ModelSceneBindingRepository sceneRepository;
    @Mock
    private ModelDefinitionRepository definitionRepository;
    @Mock
    private ModelCatalogChangePublisher catalogChangePublisher;

    private ModelSceneService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ModelSceneService(sceneRepository, definitionRepository, catalogChangePublisher);
    }

    @Test
    void listKeys_returnsEnumSsot() {
        assertThat(service.listKeys())
                .extracting(ModelSceneKeyMeta::sceneKey)
                .containsExactlyElementsOf(
                        java.util.Arrays.stream(ModelSceneKey.values()).map(ModelSceneKey::key).toList());
    }

    @Test
    void upsert_unknownSceneKey_throwsInvalid() {
        ObjectNode body = mapper.createObjectNode();
        body.put("sceneKey", "custom-scene");
        body.put("primaryModel", "qwen-plus");
        assertThatThrownBy(() -> service.upsert(body))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode().getKey())
                        .isEqualTo(ModelErrorCode.SCENE_KEY_INVALID.getKey()));
        verify(sceneRepository, never()).save(any());
        verify(catalogChangePublisher, never()).publish(any());
    }

    @Test
    void upsert_validScene_persistsAndPublishes() {
        ModelDefinitionEntity def = new ModelDefinitionEntity();
        def.setModelName("qwen-plus");
        def.setEnabled(true);
        when(definitionRepository.findByTenantIdAndModelName("default", "qwen-plus"))
                .thenReturn(Optional.of(def));
        when(sceneRepository.findByTenantIdAndSceneKey("default", "chat"))
                .thenReturn(Optional.empty());
        when(sceneRepository.save(any(ModelSceneBindingEntity.class))).thenAnswer(inv -> {
            ModelSceneBindingEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });

        ObjectNode body = mapper.createObjectNode();
        body.put("sceneKey", "chat");
        body.put("primaryModel", "qwen-plus");
        var results = service.upsert(body);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).sceneKey()).isEqualTo("chat");
        assertThat(results.get(0).primaryModel()).isEqualTo("qwen-plus");
        ArgumentCaptor<ModelSceneBindingEntity> captor = ArgumentCaptor.forClass(ModelSceneBindingEntity.class);
        verify(sceneRepository).save(captor.capture());
        assertThat(captor.getValue().getSceneKey()).isEqualTo(ModelSceneKey.CHAT.key());
        verify(catalogChangePublisher).publish(eq("default"));
    }
}
