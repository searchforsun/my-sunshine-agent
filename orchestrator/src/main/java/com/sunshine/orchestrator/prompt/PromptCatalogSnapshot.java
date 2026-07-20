package com.sunshine.orchestrator.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.routing.RoutingPlanSpec;
import com.sunshine.routing.RoutingRuleDef;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * prompt-manager Catalog 不可变视图：按 id 索引 + routing-rule 解析。
 */
@Slf4j
public final class PromptCatalogSnapshot {

    public static final String ROUTING_RULE_KIND = "routing-rule";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long catalogVersion;
    private final Map<String, PromptCatalogEntry> byId;
    private final List<RoutingRuleDef> routingRules;

    private PromptCatalogSnapshot(
            long catalogVersion,
            Map<String, PromptCatalogEntry> byId,
            List<RoutingRuleDef> routingRules) {
        this.catalogVersion = catalogVersion;
        this.byId = byId;
        this.routingRules = routingRules;
    }

    public static PromptCatalogSnapshot of(long catalogVersion, List<PromptCatalogEntry> entries) {
        Map<String, PromptCatalogEntry> byId = new HashMap<>();
        List<PromptCatalogEntry> ruleEntries = new ArrayList<>();
        if (entries != null) {
            for (PromptCatalogEntry entry : entries) {
                if (entry == null || entry.id() == null || entry.id().isBlank()) {
                    continue;
                }
                byId.put(entry.id(), entry);
                if (ROUTING_RULE_KIND.equals(entry.kind()) && entry.enabled()) {
                    ruleEntries.add(entry);
                }
            }
        }
        ruleEntries.sort(Comparator.comparingInt(PromptCatalogEntry::priority).reversed()
                .thenComparing(PromptCatalogEntry::id));
        List<RoutingRuleDef> rules = new ArrayList<>();
        for (PromptCatalogEntry entry : ruleEntries) {
            try {
                rules.add(parseRoutingRule(entry));
            } catch (Exception e) {
                log.warn("[PromptCatalog] skip rule={}: {}", entry.id(), e.getMessage());
            }
        }
        return new PromptCatalogSnapshot(catalogVersion, Map.copyOf(byId), List.copyOf(rules));
    }

    public long catalogVersion() {
        return catalogVersion;
    }

    public Map<String, PromptCatalogEntry> byId() {
        return byId;
    }

    public List<RoutingRuleDef> routingRules() {
        return routingRules;
    }

    public Optional<PromptCatalogEntry> entry(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<String> text(String id) {
        return entry(id).map(PromptCatalogEntry::contentText).filter(Objects::nonNull)
                .filter(s -> !s.isBlank());
    }

    public Optional<String> json(String id) {
        return entry(id).map(PromptCatalogEntry::contentJson).filter(Objects::nonNull)
                .filter(s -> !s.isBlank());
    }

    /** 字段映射对齐 prompt-manager PromptRoutingSupport.parse */
    static RoutingRuleDef parseRoutingRule(PromptCatalogEntry entry) throws Exception {
        String contentJson = entry.contentJson();
        if (contentJson == null || contentJson.isBlank()) {
            throw new IllegalArgumentException("contentJson empty");
        }
        JsonNode root = MAPPER.readTree(contentJson.strip());
        String matchType = textOrDefault(root, "matchType", "regex");
        String match = textOrDefault(root, "match", "any");
        List<String> patterns = stringList(root.get("patterns"));
        Map<String, List<String>> domainGroups = domainGroups(root.get("domainGroups"));
        int minDomainGroups = root.has("minDomainGroups") ? root.get("minDomainGroups").asInt(2) : 2;
        RoutingPlanSpec plan = parsePlan(root.get("plan"));
        return new RoutingRuleDef(entry.id(), entry.priority(), entry.enabled(), matchType, match,
                patterns, domainGroups, minDomainGroups, plan);
    }

    private static String textOrDefault(JsonNode root, String field, String defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return defaultValue;
        }
        String text = node.asText();
        return text != null && !text.isBlank() ? text : defaultValue;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                out.add(item.asText());
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> domainGroups(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            out.put(entry.getKey(), stringList(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    private static RoutingPlanSpec parsePlan(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new RoutingPlanSpec("react", null, Map.of());
        }
        String mode = textOrDefault(node, "mode", "react");
        String workflowId = null;
        if (node.has("workflowId") && !node.get("workflowId").isNull()) {
            String raw = node.get("workflowId").asText(null);
            if (raw != null && !raw.isBlank()) {
                workflowId = raw;
            }
        }
        return new RoutingPlanSpec(mode, workflowId, stringMap(node.get("params")));
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            out.put(entry.getKey(), value.isTextual() ? value.asText() : value.toString());
        }
        return Map.copyOf(out);
    }
}
