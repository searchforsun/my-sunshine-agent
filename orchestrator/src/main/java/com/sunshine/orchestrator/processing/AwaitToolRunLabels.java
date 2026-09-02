package com.sunshine.orchestrator.processing;

/** await_tool_run 时间线文案静态门面 — 单测可 bind；SSOT = Catalog timeline.steps.await-tool */
public final class AwaitToolRunLabels {

    private static volatile AwaitToolRunLabelService service;

    private AwaitToolRunLabels() {
    }

    public static void bind(AwaitToolRunLabelService labelService) {
        service = labelService;
    }

    public static String label() {
        return requireService().label();
    }

    public static String before() {
        return requireService().before();
    }

    public static String active() {
        return requireService().active();
    }

    public static String after() {
        return requireService().after();
    }

    /** background=true 的 sandbox__exec 步骤名（勿暴露工具 id） */
    public static String backgroundExecLabel() {
        return requireService().backgroundExecLabel();
    }

    private static AwaitToolRunLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("AwaitToolRunLabelService 未 bind");
        }
        return service;
    }
}
