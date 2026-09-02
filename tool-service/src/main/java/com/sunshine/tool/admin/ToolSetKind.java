package com.sunshine.tool.admin;

/** 默认工具集种类（按会话 kind：chat | task | all（all=声明候选并集，不进装配面）） */
public enum ToolSetKind {
    CHAT_DEFAULT(
            "chat",
            "global_chat_default",
            "tenant_chat_default",
            "global-chat-default",
            "租户 Chat 工具集",
            "tenant-%s-chat-default"),
    TASK_DEFAULT(
            "task",
            "global_task_default",
            "tenant_task_default",
            "global-task-default",
            "租户 Task 工具集",
            "tenant-%s-task-default"),
    /** kind=all：chat ∪ task 并集（仅声明候选/审计，无独立集实体） */
    ALL_DEFAULT(
            "all",
            null,
            null,
            null,
            "租户 All 工具集（chat ∪ task 并集）",
            null);

    private final String path;
    private final String globalType;
    private final String tenantType;
    private final String globalSetId;
    private final String tenantDisplayName;
    private final String tenantSetIdPattern;

    ToolSetKind(
            String path,
            String globalType,
            String tenantType,
            String globalSetId,
            String tenantDisplayName,
            String tenantSetIdPattern) {
        this.path = path;
        this.globalType = globalType;
        this.tenantType = tenantType;
        this.globalSetId = globalSetId;
        this.tenantDisplayName = tenantDisplayName;
        this.tenantSetIdPattern = tenantSetIdPattern;
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

    public static ToolSetKind fromPath(String kind) {
        return switch (kind) {
            case "chat" -> CHAT_DEFAULT;
            case "task" -> TASK_DEFAULT;
            case "all" -> ALL_DEFAULT;
            default -> throw new IllegalArgumentException("unknown tool set kind: " + kind);
        };
    }
}
