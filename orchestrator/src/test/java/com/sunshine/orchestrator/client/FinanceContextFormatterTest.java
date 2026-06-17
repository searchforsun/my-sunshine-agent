package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceContextFormatterTest {

    @Test
    @DisplayName("空结果提示勿重复调用工具")
    void emptyResult_mentionsNoRetry() {
        String ctx = FinanceContextFormatter.formatAgentContext("", "pending");
        assertThat(ctx).contains("[财务工具预查询结果]");
        assertThat(ctx).contains("list_finance_messages(status=pending)");
        assertThat(ctx).contains("请勿重复调用 list_finance_messages");
    }

    @Test
    @DisplayName("有数据时原样携带 JSON 正文")
    void withData_includesPayload() {
        String json = "{\"code\":200,\"data\":[{\"id\":1001,\"title\":\"Q2报销\"}]}";
        String ctx = FinanceContextFormatter.formatAgentContext(json, "pending");
        assertThat(ctx).contains(json);
        assertThat(ctx).contains("## 工具返回数据");
        assertThat(ctx).doesNotContain("Markdown 无序列表");
    }
}
