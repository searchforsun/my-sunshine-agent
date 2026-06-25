package com.sunshine.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSearchQueryTest {

    @Test
    void bm25QueryIncludesTenantFilter() {
        Map<String, Object> body = Bm25SearchService.buildSearchBody("报销上限", 5, "tenant-a");
        @SuppressWarnings("unchecked")
        Map<String, Object> bool = (Map<String, Object>) ((Map<String, Object>) body.get("query")).get("bool");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> must = (List<Map<String, Object>>) bool.get("must");
        assertThat(must).hasSize(2);
        assertThat(must.get(1)).isEqualTo(Map.of("term", Map.of("tenant_id", "tenant-a")));
    }

    @Test
    void milvusEscapeQuotesInTenantId() {
        assertThat(MilvusService.escapeForExpr("tenant\"a")).isEqualTo("tenant\\\"a");
    }
}
