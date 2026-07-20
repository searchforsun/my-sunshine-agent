package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.springframework.util.StringUtils;

/** Query 改写时间线展开区场景说明 — orchestrator 仅 intent/planner；RAG 见 rag-service trace */
public final class RewriteTimelineLabels {

    private static volatile TimelinePromptCatalog catalog;

    private RewriteTimelineLabels() {
    }

    public static void bind(TimelinePromptCatalog timelinePromptCatalog) {
        catalog = timelinePromptCatalog;
    }

    public static String labelFor(String scenario) {
        if (catalog != null) {
            String label = catalog.rewriteLabel(scenario);
            if (StringUtils.hasText(label)) {
                return label.strip();
            }
        }
        return "";
    }
}
