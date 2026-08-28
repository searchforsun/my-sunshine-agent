package com.sunshine.model.service;

import com.sunshine.model.entity.ModelDefinitionEntity;
import com.sunshine.model.entity.ModelProviderEntity;
import com.sunshine.model.entity.ModelSceneBindingEntity;
import com.sunshine.model.repo.ModelDefinitionRepository;
import com.sunshine.model.repo.ModelProviderRepository;
import com.sunshine.model.repo.ModelRoutePolicyRepository;
import com.sunshine.model.repo.ModelSceneBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

    @Mock
    private ModelProviderRepository providerRepository;
    @Mock
    private ModelDefinitionRepository definitionRepository;
    @Mock
    private ModelSceneBindingRepository sceneRepository;
    @Mock
    private ModelRoutePolicyRepository routeRepository;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(providerRepository, definitionRepository, sceneRepository, routeRepository);
    }

    @Test
    void publicCatalog_omitsApiKeyEnc_gatewayIncludes() {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderKey("qwen");
        provider.setDisplayName("Qwen");
        provider.setProtocol("openai-compatible");
        provider.setBaseUrl("https://example.com");
        provider.setPathPrefix("");
        provider.setApiKeyEnc("enc-secret");
        provider.setEnabled(true);
        provider.setTenantId("default");

        ModelDefinitionEntity def = new ModelDefinitionEntity();
        def.setModelName("qwen-plus");
        def.setProviderKey("qwen");
        def.setDisplayName("Qwen Plus");
        def.setContextWindow(32768);
        def.setMaxOutputTokens(8192);
        def.setEncoding("cl100k_base");
        def.setCapabilities("{\"reasoning\":false,\"multimodal\":false,\"tool_call\":true}");
        def.setUserSelectable(true);
        def.setEnabled(true);
        def.setSortOrder(1);
        def.setTenantId("default");

        ModelSceneBindingEntity scene = new ModelSceneBindingEntity();
        scene.setSceneKey("chat");
        scene.setPrimaryModel("qwen-plus");
        scene.setEnabled(true);
        scene.setTenantId("default");

        when(providerRepository.findByTenantIdOrderByProviderKeyAsc("default")).thenReturn(List.of(provider));
        when(definitionRepository.findByTenantIdOrderBySortOrderAscModelNameAsc("default"))
                .thenReturn(List.of(def));
        when(sceneRepository.findByTenantIdOrderBySceneKeyAsc("default")).thenReturn(List.of(scene));

        var pub = service.publicCatalog("default");
        assertThat(pub.providers()).hasSize(1);
        assertThat(pub.providers().get(0).apiKeyEnc()).isNull();
        assertThat(pub.definitions()).extracting(d -> d.modelName()).containsExactly("qwen-plus");
        assertThat(pub.scenes()).extracting(s -> s.sceneKey()).containsExactly("chat");

        var gw = service.gatewayCatalog("default");
        assertThat(gw.providers()).hasSize(1);
        assertThat(gw.providers().get(0).apiKeyEnc()).isEqualTo("enc-secret");
        assertThat(gw.routes()).isEmpty();
    }
}
