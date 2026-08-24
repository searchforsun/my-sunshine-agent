package com.sunshine.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistorySearchExprTest {

    @Test
    void searchExpr_withoutConvId_filtersUserAndTenantOnly() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", null);
        assertThat(expr).isEqualTo("user_id == \"u1\" && tenant_id == \"tenant-a\"");
        assertThat(expr).doesNotContain("conv_id");
    }

    @Test
    void searchExpr_withConvId_appendsConversationFilter() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", "conv-9");
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && conv_id == \"conv-9\"");
    }

    @Test
    void searchExpr_withConvId_escapesQuotes() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", "conv\"9");
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && conv_id == \"conv\\\"9\"");
    }

    @Test
    void searchExpr_withBlankConvId_ignored() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", "  ");
        assertThat(expr).isEqualTo("user_id == \"u1\" && tenant_id == \"tenant-a\"");
    }
}
