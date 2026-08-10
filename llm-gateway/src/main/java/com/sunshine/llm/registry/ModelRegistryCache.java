package com.sunshine.llm.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.config.LlmWebClientFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 resource-manager gateway catalog 加载模型注册表；订阅 Redis 热更新。
 * 启动时 definitions 为空则 fail-fast（无 Nacos 模型兜底）。
 */
@Slf4j
@Component
public class ModelRegistryCache {

    public static final String CHANNEL = "model-catalog-changed";
    private static final String DEFAULT_TENANT = "default";

    private final ObjectMapper objectMapper;
    private final ModelCryptoService cryptoService;
    private final LlmWebClientFactory webClientFactory;
    private final WebClient resourceManagerClient;
    private final String tenantId;

    private volatile Snapshot snapshot = Snapshot.empty();

    public ModelRegistryCache(
            ObjectMapper objectMapper,
            ModelCryptoService cryptoService,
            LlmWebClientFactory webClientFactory,
            WebClient.Builder webClientBuilder,
            @Value("${resource-manager.base-url:http://sunshine-resource-manager}") String resourceManagerBaseUrl,
            @Value("${model.registry.tenant-id:default}") String tenantId) {
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.webClientFactory = webClientFactory;
        this.resourceManagerClient = webClientBuilder.baseUrl(resourceManagerBaseUrl).build();
        this.tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.strip();
    }

    @PostConstruct
    public void init() {
        refreshOrFail();
    }

    /** 启动必成功；热更新失败则保留旧 snapshot */
    public void refreshOrFail() {
        GatewayModelCatalog catalog = fetchCatalog();
        applyCatalog(catalog, true);
    }

    public void refreshBestEffort() {
        try {
            GatewayModelCatalog catalog = fetchCatalog();
            applyCatalog(catalog, false);
        } catch (Exception e) {
            log.error("[ModelRegistryCache] refresh failed, keeping previous snapshot: {}", e.getMessage(), e);
        }
    }

    public Optional<ModelDefinitionView> findDefinition(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.definitionsByName.get(model));
    }

    public Optional<ModelProviderView> findProvider(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.providersByKey.get(providerKey));
    }

    public List<ModelDefinitionView> listEnabledDefinitions() {
        return snapshot.definitionsByName.values().stream()
                .filter(ModelDefinitionView::isEnabled)
                .sorted(Comparator.comparingInt(ModelDefinitionView::getSortOrder)
                        .thenComparing(ModelDefinitionView::getModelName))
                .toList();
    }

    public String decryptApiKey(ModelProviderView provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        return cryptoService.decrypt(provider.getApiKeyEnc());
    }

    public Optional<ModelSceneView> findScene(String sceneKey) {
        if (sceneKey == null || sceneKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.scenesByKey.get(sceneKey));
    }

    /**
     * 按场景绑定解析 fallback：优先 primary_model 命中当前模型的 scene；否则任意 enabled scene 的 primary。
     */
    public Optional<String> fallbackForModel(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        for (ModelSceneView scene : snapshot.scenesByKey.values()) {
            if (!scene.isEnabled()) {
                continue;
            }
            if (model.equals(scene.getPrimaryModel())
                    && scene.getFallbackModel() != null
                    && !scene.getFallbackModel().isBlank()) {
                return Optional.of(scene.getFallbackModel().strip());
            }
        }
        return Optional.empty();
    }

    private void applyCatalog(GatewayModelCatalog catalog, boolean failIfEmpty) {
        if (catalog == null) {
            catalog = GatewayModelCatalog.builder().build();
        }
        List<ModelDefinitionView> defs = catalog.getDefinitions() != null
                ? catalog.getDefinitions() : List.of();
        if (defs.isEmpty()) {
            if (failIfEmpty || snapshot.definitionsByName.isEmpty()) {
                throw new IllegalStateException(
                        "model registry catalog has no definitions; refuse to start without SSOT");
            }
            log.error("[ModelRegistryCache] refreshed catalog empty; keeping previous snapshot");
            return;
        }
        Map<String, ModelProviderView> providers = new LinkedHashMap<>();
        if (catalog.getProviders() != null) {
            for (ModelProviderView p : catalog.getProviders()) {
                if (p.getProviderKey() != null) {
                    providers.put(p.getProviderKey(), p);
                }
            }
        }
        Map<String, ModelDefinitionView> definitions = new LinkedHashMap<>();
        for (ModelDefinitionView d : defs) {
            if (d.getModelName() == null || d.getModelName().isBlank()) {
                continue;
            }
            if (d.getCapabilities() == null) {
                d.setCapabilities(ModelCapabilities.defaults());
            }
            if (d.getEncoding() == null || d.getEncoding().isBlank()) {
                d.setEncoding("cl100k_base");
            }
            definitions.put(d.getModelName(), d);
        }
        Map<String, ModelSceneView> scenes = new LinkedHashMap<>();
        if (catalog.getScenes() != null) {
            for (ModelSceneView s : catalog.getScenes()) {
                if (s.getSceneKey() != null) {
                    scenes.put(s.getSceneKey(), s);
                }
            }
        }
        boolean providersChanged = !providersEqual(snapshot.providersByKey, providers);
        this.snapshot = new Snapshot(providers, definitions, scenes);
        if (providersChanged) {
            webClientFactory.invalidateAll();
        }
        log.info("[ModelRegistryCache] loaded providers={} definitions={} scenes={}",
                providers.size(), definitions.size(), scenes.size());
    }

    private GatewayModelCatalog fetchCatalog() {
        try {
            JsonNode root = resourceManagerClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/models/catalog/gateway")
                            .queryParam("tenantId", tenantId)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
            return parseCatalog(root);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to fetch gateway model catalog from resource-manager: " + e.getMessage(), e);
        }
    }

    GatewayModelCatalog parseCatalog(JsonNode root) {
        if (root == null || root.isNull()) {
            return GatewayModelCatalog.builder().build();
        }
        JsonNode payload = root;
        if (root.has("data") && !root.get("data").isNull()) {
            payload = root.get("data");
        }
        try {
            return objectMapper.treeToValue(payload, GatewayModelCatalog.class);
        } catch (Exception e) {
            throw new IllegalStateException("invalid gateway model catalog JSON: " + e.getMessage(), e);
        }
    }

    /** 单测注入 snapshot，跳过 HTTP */
    public void replaceSnapshotForTest(GatewayModelCatalog catalog) {
        applyCatalog(catalog, true);
    }

    private static boolean providersEqual(
            Map<String, ModelProviderView> left, Map<String, ModelProviderView> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<String, ModelProviderView> e : right.entrySet()) {
            ModelProviderView a = left.get(e.getKey());
            ModelProviderView b = e.getValue();
            if (a == null || b == null) {
                return false;
            }
            if (!eq(a.getBaseUrl(), b.getBaseUrl())
                    || !eq(a.getPathPrefix(), b.getPathPrefix())
                    || !eq(a.getApiKeyEnc(), b.getApiKeyEnc())
                    || a.isEnabled() != b.isEnabled()) {
                return false;
            }
        }
        return true;
    }

    private static boolean eq(String a, String b) {
        if (a == null) {
            return b == null || b.isBlank();
        }
        if (b == null) {
            return a.isBlank();
        }
        return a.equals(b);
    }

    private record Snapshot(
            Map<String, ModelProviderView> providersByKey,
            Map<String, ModelDefinitionView> definitionsByName,
            Map<String, ModelSceneView> scenesByKey) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of());
        }
    }
}
