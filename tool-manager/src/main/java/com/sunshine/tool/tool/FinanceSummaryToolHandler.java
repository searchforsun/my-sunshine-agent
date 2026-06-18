package com.sunshine.tool.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class FinanceSummaryToolHandler implements ToolHandler {

    private final FinanceTool financeTool;

    @Override
    public String name() {
        return "summarize_finance_by_status";
    }

    @Override
    public String displayName() {
        return "统计财务消息";
    }

    @Override
    public String description() {
        return "按状态统计财务消息条数与金额合计。用户问有多少待审批、总额多少时使用。";
    }

    @Override
    public String outputSummaryKind() {
        return "finance-summary";
    }

    @Override
    public java.util.Map<String, Object> parametersSchema() {
        return ToolParamSchemas.stringParam("status", "pending | approved | all");
    }

    @Override
    public String invoke(Map<String, String> params) {
        Map<String, String> safe = params != null ? params : Map.of();
        return financeTool.summarizeFinanceByStatus(safe.getOrDefault("status", "all"));
    }
}
