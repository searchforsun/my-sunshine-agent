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
    public String invoke(Map<String, String> params) {
        Map<String, String> safe = params != null ? params : Map.of();
        return financeTool.listFinanceMessages(safe.getOrDefault("status", "all"));
    }
}
