package com.sunshine.orchestrator.processing;

/** TaskBoard 步骤文案静态门面 — 单测可 bind mock */
public final class TaskBoardStepLabels {

    private static volatile TaskBoardStepLabelService service;

    private TaskBoardStepLabels() {
    }

    public static void bind(TaskBoardStepLabelService labelService) {
        service = labelService;
    }

    public static String label() {
        return requireService().label();
    }

    public static String before() {
        return requireService().before();
    }

    public static String active(String activeTask) {
        return requireService().active(activeTask);
    }

    public static String after() {
        return requireService().after();
    }

    public static String allDone() {
        return requireService().allDone();
    }

    private static TaskBoardStepLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("TaskBoardStepLabelService 未 bind");
        }
        return service;
    }
}
