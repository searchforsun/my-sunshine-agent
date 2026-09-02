package com.sunshine.orchestrator.client;

import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagContextFormatterTest {

    private static final String CATALOG_JSON = """
            {"emptyTool":"未找到相关知识库内容。请如实告知用户，勿编造制度名称或条款。",\
            "emptyWorkflow":"[知识库检索结果]\\n未找到与用户问题直接相关的片段。",\
            "toolHeader":"知识库检索结果（共 {count} 条）：",\
            "workflowHeader":"[知识库检索结果]",\
            "citeRule":"引用文档名称须来自上方列表，内容须基于上述片段。",\
            "errorHint":"工具调用失败：知识库服务不可用（{reason}）。请如实告知用户当前无法检索企业知识库。"}""";

    private RagContextFormatter formatterWithCatalog() {
        PromptCatalogHolder holder = mock(PromptCatalogHolder.class);
        when(holder.snapshot()).thenReturn(PromptCatalogSnapshot.of(66, List.of(
                new PromptCatalogEntry("rag.tool-result", "rag", "知识库 · 工具结果格式",
                        true, 0, 1, null, CATALOG_JSON))));
        return new RagContextFormatter(holder);
    }

    private RagContextFormatter formatterWithoutCatalog() {
        PromptCatalogHolder holder = mock(PromptCatalogHolder.class);
        when(holder.snapshot()).thenReturn(PromptCatalogSnapshot.of(0, List.of()));
        return new RagContextFormatter(holder);
    }

    @Test
    void formatAgentContext_includesDocNamesAndFragments() {
        List<RagClient.RagHit> hits = List.of(
                new RagClient.RagHit("公司请假流程规范", "| 病假 | 诊断证明、病假条 |", 0f));

        String context = formatterWithCatalog().formatAgentContext(hits);

        assertThat(context).contains("[知识库检索结果]");
        assertThat(context).contains("来源文档");
        assertThat(context).contains("公司请假流程规范");
        assertThat(context).contains("诊断证明、病假条");
        assertThat(context).doesNotContain("员工出勤管理办法");
    }

    @Test
    void formatAgentContext_emptyHits() {
        String context = formatterWithCatalog().formatAgentContext(List.of());

        assertThat(context).contains("未找到");
        assertThat(context).doesNotContain("员工出勤管理办法");
    }

    @Test
    void formatToolResult_includesDocNamesAndCiteRule() {
        List<RagClient.RagHit> hits = List.of(
                new RagClient.RagHit("公司请假流程规范", "content", 0f));

        String tool = formatterWithCatalog().formatToolResult(hits);

        assertThat(tool).contains("公司请假流程规范");
        assertThat(tool).contains("知识库检索结果");
        assertThat(tool).contains("引用文档名称须来自上方列表");
    }

    @Test
    void formatToolResult_emptyHits() {
        String tool = formatterWithCatalog().formatToolResult(List.of());

        assertThat(tool).contains("未找到相关知识库内容");
    }

    @Test
    void formatHits_toolAndWorkflow_shareHitBody() {
        List<RagClient.RagHit> hits = List.of(
                new RagClient.RagHit("公司报销管理制度", "单次上限 200 元", 0.9f));

        RagContextFormatter formatter = formatterWithCatalog();
        String tool = formatter.formatHits(hits, RagContextFormatter.Mode.TOOL);
        String wf = formatter.formatHits(hits, RagContextFormatter.Mode.WORKFLOW);

        assertThat(tool).contains("公司报销管理制度");
        assertThat(wf).contains("公司报销管理制度");
        assertThat(tool).contains("200 元");
        assertThat(wf).contains("200 元");
    }

    @Test
    void formatError_replacesReasonFromCatalog() {
        String error = formatterWithCatalog().formatError("连接超时");

        assertThat(error).isEqualTo(
                "工具调用失败：知识库服务不可用（连接超时）。请如实告知用户当前无法检索企业知识库。");
    }

    @Test
    void formatError_missingCatalogKeepsMinimalFact() {
        String error = formatterWithoutCatalog().formatError("连接超时");

        assertThat(error).isEqualTo("工具调用失败：连接超时");
    }
}
