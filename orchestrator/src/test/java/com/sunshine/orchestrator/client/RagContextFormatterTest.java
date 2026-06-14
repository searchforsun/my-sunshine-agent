package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagContextFormatterTest {

    @Test
    void formatAgentContext_includesAllowedDocNamesAndFragments() {
        List<RagClient.RagHit> hits = List.of(
                new RagClient.RagHit("公司请假流程规范", "| 病假 | 诊断证明、病假条 |", 0f));

        String context = RagContextFormatter.formatAgentContext(hits);

        assertThat(context).contains("[知识库预检索结果]");
        assertThat(context).contains("允许引用的文档名称");
        assertThat(context).contains("公司请假流程规范");
        assertThat(context).contains("诊断证明、病假条");
        assertThat(context).contains("请勿重复调用 search_knowledge");
        assertThat(context).doesNotContain("员工出勤管理办法");
    }

    @Test
    void formatAgentContext_emptyHits_forbidsFabrication() {
        String context = RagContextFormatter.formatAgentContext(List.of());

        assertThat(context).contains("未找到");
        assertThat(context).contains("禁止引用、编造");
        assertThat(context).contains("员工出勤管理办法");
    }

    @Test
    void formatToolResult_matchesAgentAllowedDocs() {
        List<RagClient.RagHit> hits = List.of(
                new RagClient.RagHit("公司请假流程规范", "content", 0f));

        String tool = RagContextFormatter.formatToolResult(hits);

        assertThat(tool).contains("公司请假流程规范");
        assertThat(tool).contains("引用规则");
    }
}
