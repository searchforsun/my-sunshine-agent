package com.sunshine.tool.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class FinanceDetailToolHandler implements ToolHandler {

    private final FinanceTool financeTool;

    @Override
    public String name() {
        return "get_finance_message_detail";
    }

    @Override
    public String displayName() {
        return "查询财务消息详情";
    }

    @Override
    public String description() {
        return "按 id 查询单条财务消息详情。用户指定消息编号或要查看某条报销/付款详情时使用。";
    }

    @Override
    public java.util.Map<String, Object> parametersSchema() {
        return ToolParamSchemas.stringParam("id", "财务消息 id，如 1001");
    }

    @Override
    public String outputSummaryKind() {
        return "finance-detail";
    }

    @Override
    public String invoke(Map<String, String> params) {
        Map<String, String> safe = params != null ? params : Map.of();
        return financeTool.getFinanceMessageDetail(safe.getOrDefault("id", ""));
    }
}
