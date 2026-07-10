package com.sunshine.common.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolIdsTest {

    @Test
    void sdkAndMcp_useDoubleUnderscore() {
        assertThat(ToolIds.sdk("sunshine-finance", "list_finance_messages"))
                .isEqualTo("sdk__sunshine-finance__list_finance_messages");
        assertThat(ToolIds.mcp("demo-remote", "search_docs"))
                .isEqualTo("mcp__demo-remote__search_docs");
    }

    @Test
    void validIds_matchLlmSafePattern() {
        assertThat(ToolIds.isValid("sdk__sunshine-finance__list_finance_messages")).isTrue();
        assertThat(ToolIds.isValid("search_knowledge")).isTrue();
    }

    @Test
    void dottedIds_areInvalid() {
        assertThat(ToolIds.isValid("sdk.sunshine-finance.list_finance_messages")).isFalse();
        assertThat(ToolIds.invalidReason("sdk.sunshine-finance.list_finance_messages"))
                .contains("不允许包含 '.'");
    }
}
