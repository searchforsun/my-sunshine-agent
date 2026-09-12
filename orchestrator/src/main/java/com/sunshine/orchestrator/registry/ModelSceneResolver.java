package com.sunshine.orchestrator.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.model.ModelSceneKey;
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
 * scene key SSOT = {@link ModelSceneKey}。
 */
@Slf4j
@Component
public class ModelSceneResolver {

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
     * 解析链：agent 配置模型（非空且 enabled）→ 会话所选模型（非空且 enabled）→
     * scene primary/fallback → default → fail-fast。
     * 会话模型无效/停用时回落场景链，并标记 overrideInvalid 供时间线 warning。
     */
    public ResolvedModelScene resolve(String sceneKey, String agentConfigModel, String sessionModel) {
        if (StringUtils.hasText(agentConfigModel)) {
            Optional<ModelCatalogDefinition> agentDef = findEnabledDefinition(agentConfigModel.strip());
            if (agentDef.isPresent()) {
                return toResolvedWithScene(agentDef.get(), sceneKey, false);
            }
        }
        String sessionCandidate = StringUtils.hasText(sessionModel) ? sessionModel.strip() : null;
        if (sessionCandidate != null) {
            Optional<ModelCatalogDefinition> sessionDef = findEnabledDefinition(sessionCandidate);
            if (sessionDef.isPresent()) {
                return toResolvedWithScene(sessionDef.get(), sceneKey, false);
            }
            log.warn("[ModelSceneResolver] session model='{}' invalid/disabled, fallback to scene chain",
                    sessionCandidate);
            return resolveSceneChain(sceneKey).withOverrideInvalid(true);
        }
        return resolveSceneChain(sceneKey);
    }

    /** 显式模型（agent 配置 / 会话所选）生效时，携带所属场景的 fallback 与 extras */
    private ResolvedModelScene toResolvedWithScene(
            ModelCatalogDefinition def, String sceneKey, boolean overrideInvalid) {
        ModelCatalogScene scene = findEnabledScene(sceneKey).orElse(null);
        String fallback = scene != null ? blankToNull(scene.fallbackModel()) : null;
        return toResolved(def, fallback, sceneExtras(scene), overrideInvalid);
    }

    /** 无会话模型的调用面（intent/summarize 等内部辅助） */
    public ResolvedModelScene resolve(String sceneKey, String agentConfigModel) {
        return resolve(sceneKey, agentConfigModel, null);
    }

    /**
     * Chat 会话模型：有效即生效；无效/停用时回落 chat/default，并标记 overrideInvalid 供时间线 warning。
     */
    public ResolvedModelScene resolveChat(String conversationModel) {
        return resolve(ModelSceneKey.CHAT.key(), null, conversationModel);
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
            ResolvedModelScene resolved = resolvePrimaryOrFallback(scene.get());
            if (resolved != null) {
                return resolved;
            }
        }
        if (!ModelSceneKey.DEFAULT.key().equals(sceneKey)) {
            Optional<ModelCatalogScene> defaults = findEnabledScene(ModelSceneKey.DEFAULT.key());
            if (defaults.isPresent()) {
                ResolvedModelScene resolved = resolvePrimaryOrFallback(defaults.get());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        throw new IllegalStateException(
                "no enabled model scene for key='" + sceneKey + "' (and default missing); refuse Nacos fallback");
    }

    /** 场景内解析：primary → fallback；均不可用返回 null 交由上级链 */
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
        return null;
    }

    private ResolvedModelScene toResolved(
            ModelCatalogDefinition def,
            String fallbackModel,
            Map<String, Object> sceneExtras,
            boolean overrideInvalid) {
        ModelCapabilities caps = def.capabilities() != null ? def.capabilities() : ModelCapabilities.defaults();
        String fb = fallbackModel;
        if (fb != null && fb.equals(def.modelName())) {
            fb = null;
        }
        if (fb != null && findEnabledDefinition(fb).isEmpty()) {
            fb = null;
        }
        return new ResolvedModelScene(def.modelName(), fb, mergeExtras(def.requestExtras(), sceneExtras),
                def.contextWindow(), def.maxOutputTokens() > 0 ? def.maxOutputTokens() : 0, caps, overrideInvalid);
    }

    /**
     * extras 装配：模型级 {@code request_extras} 为基准，场景 extras 覆盖同名键。
     * 思考型模型的输出预算（{@code max_completion_tokens}）声明在模型级，场景级只做单点覆盖
     * （如 intent 的 temperature）；漏掉模型级会让 ReAct 退回全局 max-tokens 导致正文被截断。
     */
    private static Map<String, Object> mergeExtras(Map<String, Object> modelExtras, Map<String, Object> sceneExtras) {
        if ((modelExtras == null || modelExtras.isEmpty()) && (sceneExtras == null || sceneExtras.isEmpty())) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (modelExtras != null) {
            merged.putAll(modelExtras);
        }
        if (sceneExtras != null) {
            merged.putAll(sceneExtras);
        }
        return Map.copyOf(merged);
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
