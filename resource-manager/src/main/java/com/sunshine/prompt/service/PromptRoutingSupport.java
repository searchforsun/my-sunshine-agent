package com.sunshine.prompt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.prompt.entity.PromptDefinitionEntity;
import com.sunshine.prompt.entity.PromptVersionEntity;
import com.sunshine.prompt.exception.PromptErrorCode;
import com.sunshine.prompt.repo.PromptDefinitionRepository;
import com.sunshine.prompt.repo.PromptVersionRepository;
import com.sunshine.routing.RoutingPlanSpec;
import com.sunshine.routing.RoutingRuleDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptRoutingSupport {
    public static final String ROUTING_RULE_KIND = "routing-rule";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PromptDefinitionRepository definitionRepository;
    private final PromptVersionRepository versionRepository;

    public List<RoutingRuleDef> loadEnabledRules() {
        List<PromptDefinitionEntity> defs = definitionRepository.findByKindAndEnabled(ROUTING_RULE_KIND, true).stream()
                .sorted(Comparator.comparingInt(PromptDefinitionEntity::getPriority).reversed()
                        .thenComparing(PromptDefinitionEntity::getId))
                .toList();
        List<RoutingRuleDef> rules = new ArrayList<>();
        for (PromptDefinitionEntity def : defs) {
            Optional<PromptVersionEntity> active = versionRepository
                    .findByPromptIdAndVersion(def.getId(), def.getActiveVersion());
            if (active.isEmpty() || !"published".equals(active.get().getStatus())) {
                log.warn("[PromptRouting] skip rule={}: active version missing or not published", def.getId());
                continue;
            }
            String json = active.get().getContentJson();
            if (!StringUtils.hasText(json)) {
                log.warn("[PromptRouting] skip rule={}: content_json empty", def.getId());
                continue;
            }
            rules.add(parse(def.getId(), def.getPriority(), def.isEnabled(), json));
        }
        return List.copyOf(rules);
    }

    public RoutingRuleDef parse(String promptId, int priority, boolean enabled, String contentJson) {
        try {
            JsonNode root = MAPPER.readTree(contentJson.strip());
            String matchType = textOrDefault(root, "matchType", "regex");
            String match = textOrDefault(root, "match", "any");
            List<String> patterns = stringList(root.get("patterns"));
            Map<String, List<String>> domainGroups = domainGroups(root.get("domainGroups"));
            int minDomainGroups = root.has("minDomainGroups") ? root.get("minDomainGroups").asInt(2) : 2;
            RoutingPlanSpec plan = parsePlan(root.get("plan"));
            return new RoutingRuleDef(promptId, priority, enabled, matchType, match, patterns, domainGroups,
                    minDomainGroups, plan);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[PromptRouting] parse failed for {}: {}", promptId, e.getMessage());
            throw new BizException(PromptErrorCode.ROUTING_RULE_PARSE_FAILED);
        }
    }

    private static String textOrDefault(JsonNode root, String field, String defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return defaultValue;
        }
        String text = node.asText();
        return StringUtils.hasText(text) ? text : defaultValue;
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
        String workflowId = node.has("workflowId") && !node.get("workflowId").isNull()
                ? node.get("workflowId").asText(null)
                : null;
        Map<String, String> params = stringMap(node.get("params"));
        return new RoutingPlanSpec(mode, workflowId, params);
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
