package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceContextFormatterTest {

    @Test
    @DisplayName("空结果输出结构化占位")
    void emptyResult_structuredPlaceholder() {
        String ctx = FinanceContextFormatter.formatAgentContext("", "pending");
        assertThat(ctx).contains("[财务数据]");
        assertThat(ctx).contains("list_finance_messages(status=pending)");
        assertThat(ctx).contains("未返回数据");
    }

    @Test
    @DisplayName("有数据时原样携带 JSON 正文")
    void withData_includesPayload() {
        String json = "{\"code\":200,\"data\":[{\"id\":1001,\"title\":\"Q2报销\"}]}";
        String ctx = FinanceContextFormatter.formatAgentContext(json, "pending");
        assertThat(ctx).contains(json);
        assertThat(ctx).contains("[财务数据]");
    }
}
