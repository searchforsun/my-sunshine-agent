package com.sunshine.orchestrator.processing;

/** agent / RAG after 等业务摘要静态入口（配置见 Nacos agent.timeline.agent / rag-after） */
public final class SummaryStepLabels {

    private static volatile SummaryStepLabelService service;

    private SummaryStepLabels() {
    }

    public static void bind(SummaryStepLabelService labelService) {
        service = labelService;
    }

    public static String agentBefore(String clippedQuery) {
        return requireService().agentBefore(clippedQuery);
    }

    public static String agentActive(String clippedQuery) {
        return requireService().agentActive(clippedQuery);
    }

    public static String agentProgress(String clippedQuery) {
        return requireService().agentProgress(clippedQuery);
    }

    public static String agentAfter(String userQuery, String ragDetailHint) {
        return requireService().agentAfter(userQuery, ragDetailHint);
    }

    public static String ragAfter(String clippedQuery, String detail, StepMetadata metadata) {
        return requireService().ragAfter(clippedQuery, detail, metadata);
    }

    private static SummaryStepLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("SummaryStepLabelService 未 bind");
        }
        return service;
    }
}
