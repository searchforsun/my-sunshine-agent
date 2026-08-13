package com.sunshine.tool.admin;

/** 默认工具集种类（按会话 kind：chat | task）；读路径兼容旧 react-default | plan-workflow */
public enum ToolSetKind {
    CHAT_DEFAULT(
            "chat",
            "global_chat_default",
            "tenant_chat_default",
            "global-chat-default",
            "租户 Chat 工具集",
            "tenant-%s-chat-default",
            "global_react_default",
            "tenant_react_default",
            "global-react-default"),
    TASK_DEFAULT(
            "task",
            "global_task_default",
            "tenant_task_default",
            "global-task-default",
            "租户 Task 工具集",
            "tenant-%s-task-default",
            "global_plan_workflow",
            "tenant_plan_workflow",
            "global-plan-workflow");

    private final String path;
    private final String globalType;
    private final String tenantType;
    private final String globalSetId;
    private final String tenantDisplayName;
    private final String tenantSetIdPattern;
    private final String legacyGlobalType;
    private final String legacyTenantType;
    private final String legacyGlobalSetId;

    ToolSetKind(
            String path,
            String globalType,
            String tenantType,
            String globalSetId,
            String tenantDisplayName,
            String tenantSetIdPattern,
            String legacyGlobalType,
            String legacyTenantType,
            String legacyGlobalSetId) {
        this.path = path;
        this.globalType = globalType;
        this.tenantType = tenantType;
        this.globalSetId = globalSetId;
        this.tenantDisplayName = tenantDisplayName;
        this.tenantSetIdPattern = tenantSetIdPattern;
        this.legacyGlobalType = legacyGlobalType;
        this.legacyTenantType = legacyTenantType;
        this.legacyGlobalSetId = legacyGlobalSetId;
    }

    /** 对外 wire：仅 chat | task */
    public String path() {
        return path;
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

    public String legacyGlobalType() {
        return legacyGlobalType;
    }

    public String legacyTenantType() {
        return legacyTenantType;
    }

    public String legacyGlobalSetId() {
        return legacyGlobalSetId;
    }

    public static ToolSetKind fromPath(String kind) {
        return switch (kind) {
            case "chat", "react-default" -> CHAT_DEFAULT;
            case "task", "plan-workflow" -> TASK_DEFAULT;
            default -> throw new IllegalArgumentException("unknown tool set kind: " + kind);
        };
    }
}
