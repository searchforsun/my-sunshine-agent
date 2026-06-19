package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.processing.ToolStepIds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 从 chat_message.steps JSON 提取审计摘要 */
public final class StepsSummaryExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepsSummaryExtractor() {
    }

    public record Summary(List<String> toolNames, int stepCount, long totalDurationMs) {
        public static Summary empty() {
            return new Summary(List.of(), 0, 0L);
        }
    }

    public static Summary fromStepsJson(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return Summary.empty();
        }
        try {
            List<Map<String, Object>> steps = MAPPER.readValue(stepsJson, new TypeReference<>() {
            });
            LinkedHashSet<String> toolNames = new LinkedHashSet<>();
            long totalDuration = 0L;
            for (Map<String, Object> step : steps) {
                Object duration = step.get("durationMs");
                if (duration instanceof Number number) {
                    totalDuration += number.longValue();
                }
                String id = step.get("id") != null ? step.get("id").toString() : null;
                if (id == null) {
                    continue;
                }
                String baseId = ToolStepIds.stripInvokeSuffix(id);
                if (ToolStepIds.isToolStep(baseId)) {
                    String catalogName = ToolStepIds.catalogToolName(baseId);
                    if (catalogName != null && !catalogName.isBlank()) {
                        toolNames.add(catalogName);
                    }
                }
            }
            return new Summary(new ArrayList<>(toolNames), steps.size(), totalDuration);
        } catch (Exception ignored) {
            return Summary.empty();
        }
    }
}
