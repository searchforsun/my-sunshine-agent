package com.sunshine.orchestrator.processing;

/** 子 Agent 时间线文案静态门面 — 单测可 bind */
public final class SpawnSubagentLabels {

    private static volatile SpawnSubagentLabelService service;

    private SpawnSubagentLabels() {
    }

    public static void bind(SpawnSubagentLabelService labelService) {
        service = labelService;
    }

    public static String label() {
        return requireService().label();
    }

    public static String before() {
        return requireService().before();
    }

    public static String active(String labelPlaceholder) {
        return requireService().active(labelPlaceholder);
    }

    public static String after() {
        return requireService().after();
    }

    public static String afterFail() {
        return requireService().afterFail();
    }

    public static String afterCancel() {
        return requireService().afterCancel();
    }

    private static SpawnSubagentLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("SpawnSubagentLabelService 未 bind");
        }
        return service;
    }
}
