package com.sunshine.orchestrator.plan;

/** Studio 画布节点坐标（BPMN DI 等价物）；loop 可带 width/height */
public record PlanLayoutPoint(double x, double y, Double width, Double height) {

    public PlanLayoutPoint(double x, double y) {
        this(x, y, null, null);
    }

    public boolean hasSize() {
        return width != null && width > 0 && height != null && height > 0;
    }
}
