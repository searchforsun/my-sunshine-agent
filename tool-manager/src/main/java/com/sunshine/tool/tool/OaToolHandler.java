package com.sunshine.tool.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class OaToolHandler implements ToolHandler {

    private final OaTool oaTool;

    @Override
    public String name() {
        return "list_oa_tasks";
    }

    @Override
    public String displayName() {
        return "查询 OA 待办";
    }

    @Override
    public String description() {
        return "查询 OA 待办任务。用户问请假审批、合同会签、出差/用印等待办时使用。";
    }

    @Override
    public java.util.Map<String, Object> parametersSchema() {
        return ToolParamSchemas.stringParam("status", "pending | done | all");
    }

    @Override
    public String outputSummaryKind() {
        return "oa-tasks";
    }

    @Override
    public String invoke(Map<String, String> params) {
        Map<String, String> safe = params != null ? params : Map.of();
        return oaTool.listOaTasks(safe.getOrDefault("status", "pending"));
    }
}
