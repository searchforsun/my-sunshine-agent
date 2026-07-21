package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.config.dto.ConfigFieldSchema;
import com.sunshine.rag.admin.config.dto.ConfigSchemaResponse;
import com.sunshine.rag.admin.config.dto.ConfigScopeGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagConfigSchemaService {

    private static final List<String> SEARCH_STRATEGIES = List.of("vector", "hybrid", "hybrid+rerank");

    private final ConfigVersionService configVersionService;
    private final EffectiveConfigResolver effectiveConfigResolver;

    public ConfigSchemaResponse getSchema(String tenantId, String kbId) {
        String kid = kbId != null && !kbId.isBlank() ? kbId : "default";
        Map<String, Object> payload = configVersionService.getEffective(tenantId, kid, "published", null);
        List<ConfigScopeGroup> scopes = new ArrayList<>();
        for (ConfigScope scope : ConfigScope.values()) {
            scopes.add(new ConfigScopeGroup(
                    scope.id(),
                    scope.label(),
                    scope.dataId(),
                    scope.nacosPath(),
                    fieldsFor(scope, payload)));
        }
        EffectiveRagConfig effective = effectiveConfigResolver.resolve(tenantId, kid).retrieval();
        return new ConfigSchemaResponse(scopes, effective);
    }

    private List<ConfigFieldSchema> fieldsFor(ConfigScope scope, Map<String, Object> payload) {
        return switch (scope) {
            case RAG_SEARCH -> List.of(
                    field("minScore", "向量/IP 下限", "number", 0.0, 1.0, scope.id(), bundlePath(payload, "search", "minScore"), null),
                    field("strategy", "默认策略", "enum", null, null, scope.id(), bundlePath(payload, "search", "strategy"), SEARCH_STRATEGIES),
                    field("rrfK", "RRF 常数 k", "number", 1, 200, scope.id(), bundlePath(payload, "search", "rrfK"), null),
                    field("hybridPoolSize", "混合召回池", "number", 1, 100, scope.id(), bundlePath(payload, "search", "hybridPoolSize"), null),
                    field("defaultTopK", "默认 TopK", "number", 1, 20, scope.id(), bundlePath(payload, "search", "defaultTopK"), null));
            case RAG_RERANK -> List.of(
                    field("enabled", "启用 Rerank", "boolean", null, null, scope.id(), bundlePath(payload, "rerank", "enabled"), null),
                    field("minScore", "Rerank 下限", "number", 0.0, 1.0, scope.id(), bundlePath(payload, "rerank", "minScore"), null),
                    field("minRelevance", "Relevance 下限", "number", 0.0, 1.0, scope.id(), bundlePath(payload, "rerank", "minRelevance"), null));
            case REWRITE_RAG -> List.of(
                    field("enabled", "启用 RAG 改写", "boolean", null, null, scope.id(), bundlePath(payload, "rewrite", "rag", "enabled"), null),
                    field("model", "模型", "string", null, null, scope.id(), bundlePath(payload, "rewrite", "rag", "model"), null),
                    field("systemPrompt", "System Prompt", "text", null, null, scope.id(), bundlePath(payload, "rewrite", "rag", "systemPrompt"), null));
            case REWRITE_HYDE -> List.of(
                    field("enabled", "启用 HyDE", "boolean", null, null, scope.id(), bundlePath(payload, "rewrite", "hyde", "enabled"), null),
                    field("model", "模型", "string", null, null, scope.id(), bundlePath(payload, "rewrite", "hyde", "model"), null),
                    field("maxChars", "最大字符", "number", 128, 2000, scope.id(), bundlePath(payload, "rewrite", "hyde", "maxChars"), null),
                    field("systemPrompt", "System Prompt", "text", null, null, scope.id(), bundlePath(payload, "rewrite", "hyde", "systemPrompt"), null));
            case REWRITE_EMPTY_RECALL -> List.of(
                    field("enabled", "启用零命中改写", "boolean", null, null, scope.id(), bundlePath(payload, "rewrite", "emptyRecall", "enabled"), null),
                    field("model", "模型", "string", null, null, scope.id(), bundlePath(payload, "rewrite", "emptyRecall", "model"), null),
                    field("maxAlternatives", "备选 query 数", "number", 1, 5, scope.id(), bundlePath(payload, "rewrite", "emptyRecall", "maxAlternatives"), null),
                    field("systemPrompt", "System Prompt", "text", null, null, scope.id(), bundlePath(payload, "rewrite", "emptyRecall", "systemPrompt"), null));
        };
    }

    private static Object bundlePath(Map<String, Object> payload, String... path) {
        return ConfigBundlePayload.pathValue(payload, path);
    }

    private static ConfigFieldSchema field(
            String fieldId,
            String label,
            String type,
            Object min,
            Object max,
            String scope,
            Object currentValue,
            List<String> enumValues) {
        return new ConfigFieldSchema(fieldId, label, type, min, max, scope, currentValue, enumValues);
    }
}
