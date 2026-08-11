package com.sunshine.orchestrator.processing;

/** request_decision 时间线文案静态门面 — 单测可 bind */
public final class DecisionLabels {

    private static volatile DecisionLabelService service;

    private DecisionLabels() {
    }

    public static void bind(DecisionLabelService labelService) {
        service = labelService;
    }

    public static String label() {
        return requireService().label();
    }

    public static String before() {
        return requireService().before();
    }

    public static String active(String question) {
        return requireService().active(question);
    }

    public static String after(String choice) {
        return requireService().after(choice);
    }

    public static String afterFail() {
        return requireService().afterFail();
    }

    public static String afterTimeout() {
        return requireService().afterTimeout();
    }

    public static String afterCancel() {
        return requireService().afterCancel();
    }

    private static DecisionLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("DecisionLabelService 未 bind");
        }
        return service;
    }
}
