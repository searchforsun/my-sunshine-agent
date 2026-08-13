package com.sunshine.orchestrator.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties.AgentTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.HitlTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.IntentTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.RagAfterTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.SandboxTimeline;
import com.sunshine.orchestrator.config.AgentPromptProperties.StepTimeline;
import com.sunshine.orchestrator.rewrite.QueryRewriteScenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 时间线 / rewrite.timeline 文案 — prompt-manager Catalog JSON（kebab-case）。
 * 缺条目 → 空 POJO + warn（与 {@link PromptComposer} catalogText 一致；禁止 Java/Nacos 影子兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelinePromptCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final PromptCatalogHolder catalogHolder;

    /**
     * 单测 fixture：把 Java 内嵌样例写入 Catalog Snapshot，再经正常读取路径取文案。
     * 生产路径禁止走此方法背后的样例表。
     */
    public static TimelinePromptCatalog withDefaults() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        holder.replace(PromptCatalogSnapshot.of(0L, fixtureEntries()));
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
        log.warn("[TimelinePromptCatalog] catalog missing id=timeline.steps.{}", name);
        return new StepTimeline();
    }

    /** 仅聚合 Catalog 细项 {@code timeline.steps.*}（意图步走 {@link #intent()}，无整包兜底）。 */
    public LinkedHashMap<String, StepTimeline> steps() {
        LinkedHashMap<String, StepTimeline> out = new LinkedHashMap<>();
        for (String id : catalogHolder.snapshot().byId().keySet()) {
            if (id != null && id.startsWith("timeline.steps.")) {
                String name = id.substring("timeline.steps.".length());
                parseJsonOpt(id, StepTimeline.class).ifPresent(s -> out.put(name, s));
            }
        }
        return out;
    }

    public HitlTimeline hitl() {
        return parseJson("timeline.hitl", HitlTimeline.class, HitlTimeline::new);
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
            log.warn("[TimelinePromptCatalog] catalog missing id=rewrite.timeline");
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

    private <T> T parseJson(String id, Class<T> type, Supplier<T> empty) {
        Optional<T> parsed = parseJsonOpt(id, type);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        log.warn("[TimelinePromptCatalog] catalog missing id={}", id);
        return empty.get();
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

    /** 单测 fixture：Java 样例 → Catalog JSON 条目（kebab-case） */
    static List<PromptCatalogEntry> fixtureEntries() {
        List<PromptCatalogEntry> entries = new ArrayList<>();
        AgentPromptProperties.Timeline fixture = AgentPromptProperties.Timeline.fixture();
        entries.add(jsonEntry("timeline.intent", writeJson(fixture.getIntent())));
        entries.add(jsonEntry("timeline.hitl", writeJson(fixture.getHitl())));
        entries.add(jsonEntry("timeline.agent", writeJson(fixture.getAgent())));
        entries.add(jsonEntry("timeline.rag-after", writeJson(fixture.getRagAfter())));
        entries.add(jsonEntry("timeline.sandbox", writeJson(fixture.getSandbox())));
        for (Map.Entry<String, StepTimeline> e : fixture.getSteps().entrySet()) {
            entries.add(jsonEntry("timeline.steps." + e.getKey(), writeJson(e.getValue())));
        }
        entries.add(jsonEntry("rewrite.timeline", "{\"intent\":\"补全问句\",\"planner\":\"优化规划输入\"}"));
        return entries;
    }

    private static PromptCatalogEntry jsonEntry(String id, String contentJson) {
        return new PromptCatalogEntry(id, "timeline", id, true, 0, 1, null, contentJson);
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("fixture serialize failed: " + e.getMessage(), e);
        }
    }
}
