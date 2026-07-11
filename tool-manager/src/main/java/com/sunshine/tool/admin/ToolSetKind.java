package com.sunshine.tool.admin;

/** 工具集种类常量（全局 + 租户覆盖） */
public enum ToolSetKind {
    REACT_DEFAULT(
            "global_react_default",
            "tenant_react_default",
            "global-react-default",
            "租户 ReAct 工具集",
            "tenant-%s-react-default"),
    PLAN_WORKFLOW(
            "global_plan_workflow",
            "tenant_plan_workflow",
            "global-plan-workflow",
            "租户 Plan-Workflow 工具集",
            "tenant-%s-plan-workflow");

    private final String globalType;
    private final String tenantType;
    private final String globalSetId;
    private final String tenantDisplayName;
    private final String tenantSetIdPattern;

    ToolSetKind(String globalType, String tenantType, String globalSetId, String tenantDisplayName, String tenantSetIdPattern) {
        this.globalType = globalType;
        this.tenantType = tenantType;
        this.globalSetId = globalSetId;
        this.tenantDisplayName = tenantDisplayName;
        this.tenantSetIdPattern = tenantSetIdPattern;
    }

    public String globalType() {
        return globalType;
    }

    public String tenantType() {
        return tenantType;
    }

    public String globalSetId() {
        return globalSetId;
    }

    public String tenantDisplayName() {
        return tenantDisplayName;
    }

    public String tenantSetIdPattern() {
        return tenantSetIdPattern;
    }

    public static ToolSetKind fromPath(String kind) {
        return switch (kind) {
            case "react-default" -> REACT_DEFAULT;
            case "plan-workflow" -> PLAN_WORKFLOW;
            default -> throw new IllegalArgumentException("unknown tool set kind: " + kind);
        };
    }
}
