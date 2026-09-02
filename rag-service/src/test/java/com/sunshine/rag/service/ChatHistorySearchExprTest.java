package com.sunshine.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void searchExpr_withScene_appendsSceneFilter() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", (String) null, "task", null, null);
        assertThat(expr).isEqualTo("user_id == \"u1\" && tenant_id == \"tenant-a\" && scene == \"task\"");
    }

    @Test
    void searchExpr_withLayers_appendsLayerInFilter() {
        String expr = ChatHistoryMilvusService.buildSearchExpr(
                "u1", "tenant-a", (String) null, null, List.of("body", "process"), null);
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && layer IN [\"body\", \"process\"]");
    }

    @Test
    void searchExpr_withStatus_appendsStatusFilter() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", (String) null, null, null, "active");
        assertThat(expr).isEqualTo("user_id == \"u1\" && tenant_id == \"tenant-a\" && status == \"active\"");
    }

    @Test
    void searchExpr_sceneAndLayersAndStatus_composed() {
        String expr = ChatHistoryMilvusService.buildSearchExpr(
                "u1", "tenant-a", "conv-9", "task", List.of("body", "process"), "active");
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && conv_id == \"conv-9\""
                        + " && scene == \"task\" && layer IN [\"body\", \"process\"] && status == \"active\"");
    }

    @Test
    void searchExpr_withConvIds_appendsConversationInFilter() {
        String expr = ChatHistoryMilvusService.buildSearchExpr(
                "u1", "tenant-a", List.of("conv-1", "conv-2"), null, null, null);
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && conv_id IN [\"conv-1\", \"conv-2\"]");
    }

    @Test
    void searchExpr_withConvIds_escapesQuotes() {
        String expr = ChatHistoryMilvusService.buildSearchExpr(
                "u1", "tenant-a", List.of("conv\"1", "conv-2"), "task", List.of("body"), null);
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && conv_id IN [\"conv\\\"1\", \"conv-2\"]"
                        + " && scene == \"task\" && layer IN [\"body\"]");
    }

    @Test
    void searchExpr_emptyConvIds_omitsConversationFilter() {
        String expr = ChatHistoryMilvusService.buildSearchExpr(
                "u1", "tenant-a", List.of(), "task", null, null);
        assertThat(expr).isEqualTo("user_id == \"u1\" && tenant_id == \"tenant-a\" && scene == \"task\"");
    }

    @Test
    void searchExpr_singleConvIdKeepsEqualitySemantics() {
        String expr = ChatHistoryMilvusService.buildSearchExpr("u1", "tenant-a", "conv-9");
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && conv_id == \"conv-9\"");
    }

    @Test
    void searchExpr_layerEscapesQuotes() {
        String expr = ChatHistoryMilvusService.buildSearchExpr(
                "u1", "tenant-a", (String) null, null, List.of("bo\"dy"), null);
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\" && layer IN [\"bo\\\"dy\"]");
    }

    @Test
    void recentVectorsExpr_filtersUserTenantSceneLayerAndTime() {
        String expr = ChatHistoryMilvusService.buildRecentVectorsExpr(
                "u1", "tenant-a", "chat", "semantic", 1_780_000_000_000L);
        assertThat(expr).isEqualTo(
                "user_id == \"u1\" && tenant_id == \"tenant-a\""
                        + " && scene == \"chat\" && layer == \"semantic\""
                        + " && created_at >= 1780000000000");
    }

    @Test
    void recentVectorsExpr_layerIsolatedFromBody() {
        // 关键回归：semantic/process 去重窗口必须限定同 layer，避免与 body 原文跨层误判重复
        String semantic = ChatHistoryMilvusService.buildRecentVectorsExpr("u1", "t", null, "semantic", 0);
        String body = ChatHistoryMilvusService.buildRecentVectorsExpr("u1", "t", null, "body", 0);
        assertThat(semantic).isEqualTo("user_id == \"u1\" && tenant_id == \"t\" && layer == \"semantic\"");
        assertThat(body).isEqualTo("user_id == \"u1\" && tenant_id == \"t\" && layer == \"body\"");
        assertThat(semantic).isNotEqualTo(body);
    }

    @Test
    void recentVectorsExpr_omitsLayerWhenNull() {
        String expr = ChatHistoryMilvusService.buildRecentVectorsExpr("u1", "tenant-a", "task", null, 0);
        assertThat(expr).isEqualTo("user_id == \"u1\" && tenant_id == \"tenant-a\" && scene == \"task\"");
    }
}
