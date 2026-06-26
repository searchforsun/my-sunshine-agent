package com.sunshine.tool.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/** 模拟 OA 写操作 — HITL 验收用 */
@Component
public class ApproveOaTaskToolHandler implements ToolHandler {

    @Override
    public String name() {
        return "approve_oa_task";
    }

    @Override
    public String displayName() {
        return "审批 OA 待办";
    }

    @Override
    public String description() {
        return "审批指定 OA 待办任务（写操作）。用户明确要求通过/批准某条待办时直接调用本工具，参数 taskId 为待办编号；执行确认由平台时间线处理。";
    }

    @Override
    public String sideEffect() {
        return "write";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolParamSchemas.stringParam("taskId", "待办任务 ID，如 T1001");
    }

    @Override
    public String invoke(Map<String, String> params) {
        Map<String, String> safe = params != null ? params : Map.of();
        String taskId = safe.getOrDefault("taskId", "unknown");
        return "已审批待办 " + taskId + "（模拟写操作）";
    }
}
