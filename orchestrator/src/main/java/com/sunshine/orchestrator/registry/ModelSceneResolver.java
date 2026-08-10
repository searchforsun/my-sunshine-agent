package com.sunshine.orchestrator.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 resource-manager 公共 Catalog 解析 scene → 有效模型；订阅 Redis 热更新。
 * 启动 definitions 为空则 fail-fast（禁止 Nacos 模型名兜底，D10）。
 */
@Slf4j
@Component
public class ModelSceneResolver {

    public static final String CHANNEL = "model-catalog-changed";
    public static final String SCENE_DEFAULT = "default";
    public static final String SCENE_CHAT = "chat";
    public static final String SCENE_INTENT = "intent";
    public static final String SCENE_PLANNER = "planner";
    public static final String SCENE_REWRITE_INTENT = "rewrite.intent";
    public static final String SCENE_REWRITE_PLANNER = "rewrite.planner";
    public static final String SCENE_TITLE = "title";
    public static final String SCENE_SUBAGENT = "subagent";

    private static final String DEFAULT_TENANT = "default";

    private final ObjectMapper objectMapper;
    private final WebClient resourceManagerClient;
    private final String tenantId;

    private volatile Snapshot snapshot = Snapshot.empty();

    public ModelSceneResolver(
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            @Value("${resource-manager.base-url:http://sunshine-resource-manager}") String resourceManagerBaseUrl,
            @Value("${model.registry.tenant-id:default}") String tenantId) {
        this.objectMapper = objectMapper;
        this.resourceManagerClient = webClientBuilder.baseUrl(resourceManagerBaseUrl).build();
        this.tenantId = StringUtils.hasText(tenantId) ? tenantId.strip() : DEFAULT_TENANT;
    }

    @PostConstruct
    public void init() {
        refreshOrFail();
    }

    /** 启动必成功；热更新失败保留旧 snapshot */
    public void refreshOrFail() {
        ModelCatalogPayload catalog = fetchCatalog();
        applyCatalog(catalog, true);
    }

    public void refreshBestEffort() {
        try {
            ModelCatalogPayload catalog = fetchCatalog();
            applyCatalog(catalog, false);
        } catch (Exception e) {
            log.error("[ModelSceneResolver] refresh failed, keeping previous snapshot: {}", e.getMessage(), e);
        }
    }

    /**
     * D10：modelOverride（非空且 enabled）→ scene primary/fallback → default → fail-fast。
     */
    public ResolvedModelScene resolve(String sceneKey, String modelOverride) {
        if (StringUtils.hasText(modelOverride)) {
            Optional<ModelCatalogDefinition> overrideDef = findEnabledDefinition(modelOverride.strip());
            if (overrideDef.isPresent()) {
                ModelCatalogScene scene = findEnabledScene(sceneKey).orElse(null);
                String fallback = scene != null ? blankToNull(scene.fallbackModel()) : null;
                return toResolved(overrideDef.get(), fallback, sceneExtras(scene), false);
            }
        }
        return resolveSceneChain(sceneKey);
    }

    /**
     * Chat 会话 override：无效/停用时回落 chat→default，并标记 overrideInvalid 供时间线 warning。
     */
    public ResolvedModelScene resolveChat(String conversationModel) {
        if (!StringUtils.hasText(conversationModel)) {
            return resolve(SCENE_CHAT, null);
        }
        String override = conversationModel.strip();
        Optional<ModelCatalogDefinition> def = findEnabledDefinition(override);
        if (def.isPresent()) {
            return resolve(SCENE_CHAT, override);
        }
        log.warn("[ModelSceneResolver] chat override invalid/disabled model='{}', fallback to chat/default scene",
                override);
        return resolve(SCENE_CHAT, null).withOverrideInvalid(true);
    }

    public Optional<ModelCatalogDefinition> findDefinition(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.definitionsByName.get(modelName.strip()));
    }

    /** 注册表窗口；缺 meta 时拒绝（无 Nacos default-model-window） */
    public int contextWindowFor(String modelName) {
        ModelCatalogDefinition def = findDefinition(modelName)
                .orElseThrow(() -> new IllegalStateException(
                        "model definition missing for window lookup: " + modelName));
        if (def.contextWindow() <= 0) {
            throw new IllegalStateException("model contextWindow invalid: " + modelName);
        }
        return def.contextWindow();
    }

    /** 供 ModelWindowCache 整体替换 */
    public Map<String, Integer> allContextWindows() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ModelCatalogDefinition d : snapshot.definitionsByName.values()) {
            if (d.modelName() != null && d.contextWindow() > 0) {
                map.put(d.modelName(), d.contextWindow());
            }
        }
        return Map.copyOf(map);
    }

    /** 单测注入 snapshot，跳过 HTTP */
    public void replaceSnapshotForTest(List<ModelCatalogDefinition> definitions, List<ModelCatalogScene> scenes) {
        applyCatalog(new ModelCatalogPayload(List.of(), definitions, scenes), true);
    }

    private ResolvedModelScene resolveSceneChain(String sceneKey) {
        Optional<ModelCatalogScene> scene = findEnabledScene(sceneKey);
        if (scene.isPresent()) {
            return resolvePrimaryOrFallback(scene.get());
        }
        if (!SCENE_DEFAULT.equals(sceneKey)) {
            Optional<ModelCatalogScene> defaults = findEnabledScene(SCENE_DEFAULT);
            if (defaults.isPresent()) {
                return resolvePrimaryOrFallback(defaults.get());
            }
        }
        throw new IllegalStateException(
                "no enabled model scene for key='" + sceneKey + "' (and default missing); refuse Nacos fallback");
    }

    private ResolvedModelScene resolvePrimaryOrFallback(ModelCatalogScene scene) {
        Optional<ModelCatalogDefinition> primary = findEnabledDefinition(scene.primaryModel());
        if (primary.isPresent()) {
            return toResolved(primary.get(), blankToNull(scene.fallbackModel()), sceneExtras(scene), false);
        }
        String fallback = blankToNull(scene.fallbackModel());
        if (fallback != null) {
            Optional<ModelCatalogDefinition> fb = findEnabledDefinition(fallback);
            if (fb.isPresent()) {
                log.warn("[ModelSceneResolver] scene={} primary='{}' unavailable, using fallback='{}'",
                        scene.sceneKey(), scene.primaryModel(), fallback);
                return toResolved(fb.get(), null, sceneExtras(scene), false);
            }
        }
        if (!SCENE_DEFAULT.equals(scene.sceneKey())) {
            return resolveSceneChain(SCENE_DEFAULT);
        }
        throw new IllegalStateException(
                "scene '" + scene.sceneKey() + "' primary/fallback unavailable; refuse Nacos fallback");
    }

    private ResolvedModelScene toResolved(
            ModelCatalogDefinition def,
            String fallbackModel,
            Map<String, Object> extras,
            boolean overrideInvalid) {
        ModelCapabilities caps = def.capabilities() != null ? def.capabilities() : ModelCapabilities.defaults();
        String fb = fallbackModel;
        if (fb != null && fb.equals(def.modelName())) {
            fb = null;
        }
        if (fb != null && findEnabledDefinition(fb).isEmpty()) {
            fb = null;
        }
        return new ResolvedModelScene(def.modelName(), fb, extras, def.contextWindow(),
                def.maxOutputTokens() > 0 ? def.maxOutputTokens() : 0, caps, overrideInvalid);
    }

    private Optional<ModelCatalogDefinition> findEnabledDefinition(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return Optional.empty();
        }
        ModelCatalogDefinition def = snapshot.definitionsByName.get(modelName.strip());
        if (def == null || !def.enabled()) {
            return Optional.empty();
        }
        return Optional.of(def);
    }

    private Optional<ModelCatalogScene> findEnabledScene(String sceneKey) {
        if (!StringUtils.hasText(sceneKey)) {
            return Optional.empty();
        }
        ModelCatalogScene scene = snapshot.scenesByKey.get(sceneKey.strip());
        if (scene == null || !scene.enabled()) {
            return Optional.empty();
        }
        return Optional.of(scene);
    }

    private static Map<String, Object> sceneExtras(ModelCatalogScene scene) {
        return scene != null && scene.extras() != null ? scene.extras() : Map.of();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private void applyCatalog(ModelCatalogPayload catalog, boolean failIfEmpty) {
        List<ModelCatalogDefinition> defs = catalog != null && catalog.definitions() != null
                ? catalog.definitions() : List.of();
        if (defs.isEmpty()) {
            if (failIfEmpty || snapshot.definitionsByName.isEmpty()) {
                throw new IllegalStateException(
                        "model registry catalog has no definitions; refuse to start without SSOT");
            }
            log.error("[ModelSceneResolver] refreshed catalog empty; keeping previous snapshot");
            return;
        }
        Map<String, ModelCatalogDefinition> definitions = new LinkedHashMap<>();
        for (ModelCatalogDefinition d : defs) {
            if (d == null || !StringUtils.hasText(d.modelName())) {
                continue;
            }
            definitions.put(d.modelName().strip(), d);
        }
        Map<String, ModelCatalogScene> scenes = new LinkedHashMap<>();
        if (catalog.scenes() != null) {
            for (ModelCatalogScene s : catalog.scenes()) {
                if (s != null && StringUtils.hasText(s.sceneKey())) {
                    scenes.put(s.sceneKey().strip(), s);
                }
            }
        }
        this.snapshot = new Snapshot(definitions, scenes);
        log.info("[ModelSceneResolver] loaded definitions={} scenes={}", definitions.size(), scenes.size());
    }

    private ModelCatalogPayload fetchCatalog() {
        try {
            JsonNode root = resourceManagerClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/models/catalog")
                            .queryParam("tenantId", tenantId)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
            return parseCatalog(root);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to fetch public model catalog from resource-manager: " + e.getMessage(), e);
        }
    }

    ModelCatalogPayload parseCatalog(JsonNode root) {
        if (root == null || root.isNull()) {
            return new ModelCatalogPayload(List.of(), List.of(), List.of());
        }
        JsonNode payload = root;
        if (root.has("data") && !root.get("data").isNull()) {
            payload = root.get("data");
        }
        try {
            return objectMapper.treeToValue(payload, ModelCatalogPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("invalid model catalog JSON: " + e.getMessage(), e);
        }
    }

    private record Snapshot(
            Map<String, ModelCatalogDefinition> definitionsByName,
            Map<String, ModelCatalogScene> scenesByKey) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }
    }
}
