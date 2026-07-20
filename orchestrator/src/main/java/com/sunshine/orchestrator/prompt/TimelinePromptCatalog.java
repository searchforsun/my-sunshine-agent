package com.sunshine.orchestrator.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties.AgentTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.HitlTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.IntentTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.PlanApprovalTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.RagAfterTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.SandboxTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.StepTimeline;
import com.sunshine.orchestrator.rewrite.QueryRewriteScenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 时间线 / rewrite.timeline 文案 — prompt-manager Catalog JSON（kebab-case）。
 * 缺条目时回退 {@link AgentPromptProperties} 内嵌 Java 默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelinePromptCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final PromptCatalogHolder catalogHolder;

    /** 单测：空 Catalog → 全部 Java 默认文案 */
    public static TimelinePromptCatalog withDefaults() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        holder.replace(PromptCatalogSnapshot.of(0L, java.util.List.of()));
        return new TimelinePromptCatalog(holder);
    }

    public IntentTimeline intent() {
        return parseJson("timeline.intent", IntentTimeline.class, IntentTimeline::new);
    }

    public StepTimeline step(String name) {
        if (!StringUtils.hasText(name)) {
            return new StepTimeline();
        }
        Optional<StepTimeline> direct = parseJsonOpt("timeline.steps." + name, StepTimeline.class);
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<StepTimeline> fromPack = stepFromPack(name);
        if (fromPack.isPresent()) {
            return fromPack.get();
        }
        StepTimeline fallback = AgentPromptProperties.Timeline.defaultSteps().get(name);
        return fallback != null ? fallback : new StepTimeline();
    }

    public LinkedHashMap<String, StepTimeline> steps() {
        LinkedHashMap<String, StepTimeline> out = new LinkedHashMap<>(AgentPromptProperties.Timeline.defaultSteps());
        catalogHolder.snapshot().json("timeline.steps").ifPresent(pack -> {
            try {
                JsonNode root = MAPPER.readTree(pack);
                if (root != null && root.isObject()) {
                    root.fields().forEachRemaining(e -> {
                        try {
                            out.put(e.getKey(), MAPPER.treeToValue(e.getValue(), StepTimeline.class));
                        } catch (Exception ex) {
                            log.warn("[TimelinePromptCatalog] skip pack step={}: {}", e.getKey(), ex.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("[TimelinePromptCatalog] timeline.steps pack parse failed: {}", e.getMessage());
            }
        });
        for (String id : catalogHolder.snapshot().byId().keySet()) {
            if (id != null && id.startsWith("timeline.steps.") && !"timeline.steps".equals(id)) {
                String name = id.substring("timeline.steps.".length());
                parseJsonOpt(id, StepTimeline.class).ifPresent(s -> out.put(name, s));
            }
        }
        return out;
    }

    public HitlTimeline hitl() {
        return parseJson("timeline.hitl", HitlTimeline.class, HitlTimeline::new);
    }

    public PlanApprovalTimeline planApproval() {
        return parseJson("timeline.plan-approval", PlanApprovalTimeline.class, PlanApprovalTimeline::new);
    }

    public AgentTimeline agent() {
        return parseJson("timeline.agent", AgentTimeline.class, AgentTimeline::new);
    }

    public RagAfterTimeline ragAfter() {
        return parseJson("timeline.rag-after", RagAfterTimeline.class, RagAfterTimeline::new);
    }

    public SandboxTimeline sandbox() {
        return parseJson("timeline.sandbox", SandboxTimeline.class, SandboxTimeline::new);
    }

    /** rewrite.timeline JSON：intent / planner 场景说明 */
    public String rewriteLabel(String scenario) {
        if (scenario == null) {
            return "";
        }
        Optional<JsonNode> root = catalogHolder.snapshot().json("rewrite.timeline").map(j -> {
            try {
                return MAPPER.readTree(j);
            } catch (Exception e) {
                return null;
            }
        });
        if (root.isEmpty() || root.get() == null) {
            return "";
        }
        JsonNode node = root.get();
        String key = switch (scenario) {
            case String s when QueryRewriteScenario.INTENT.matches(s) -> "intent";
            case String s when QueryRewriteScenario.PLANNER.matches(s) -> "planner";
            default -> null;
        };
        if (key == null) {
            return "";
        }
        JsonNode label = node.get(key);
        return label != null && label.isTextual() ? label.asText() : "";
    }

    private Optional<StepTimeline> stepFromPack(String name) {
        return catalogHolder.snapshot().json("timeline.steps").flatMap(pack -> {
            try {
                JsonNode root = MAPPER.readTree(pack);
                JsonNode node = root != null ? root.get(name) : null;
                if (node == null || node.isNull()) {
                    return Optional.empty();
                }
                return Optional.of(MAPPER.treeToValue(node, StepTimeline.class));
            } catch (Exception e) {
                log.warn("[TimelinePromptCatalog] pack step={} failed: {}", name, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private <T> T parseJson(String id, Class<T> type, Supplier<T> fallback) {
        return parseJsonOpt(id, type).orElseGet(fallback);
    }

    private <T> Optional<T> parseJsonOpt(String id, Class<T> type) {
        return catalogHolder.snapshot().json(id).flatMap(json -> {
            try {
                return Optional.of(MAPPER.readValue(json, type));
            } catch (Exception e) {
                log.warn("[TimelinePromptCatalog] parse id={} failed: {}", id, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
