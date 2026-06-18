package com.sunshine.tool.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 财务工具 — 委托 FinanceTool 实现
 */
@Component
@RequiredArgsConstructor
public class FinanceToolHandler implements ToolHandler {

    private final FinanceTool financeTool;

    @Override
    public String name() {
        return "list_finance_messages";
    }

    @Override
    public String displayName() {
        return "查询待审批财务消息";
    }

    @Override
    public String description() {
        return "查询财务待办/审批消息。用户问报销、付款、预算、待审批消息时调用。status: pending|approved|all";
    }

    @Override
    public String outputSummaryKind() {
        return "finance-list";
    }

    @Override
    public java.util.Map<String, Object> parametersSchema() {
        return ToolParamSchemas.stringParam("status", "pending | approved | all");
    }

    @Override
    public String invoke(Map<String, String> params) {
        Map<String, String> safe = params != null ? params : Map.of();
        return financeTool.listFinanceMessages(safe.getOrDefault("status", "all"));
    }
}
