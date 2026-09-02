package com.sunshine.orchestrator.agent;

import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.ToolRetrievalClient;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 5.5 工具语义检索 ToolRetrievalService 单测：
 * 开关判定 / 恒注入（内置·沙箱·HITL）/ 检索 Top-K 收敛 / 回退全量 / Tier 0 目录确定性排序 / 参数摘要。
 */
class ToolRetrievalServiceTest {

    private AgentExecutionProperties props(String mode, int topK, float minScore, boolean fallbackFull) {
        AgentExecutionProperties.React.ToolInject inject = new AgentExecutionProperties.React.ToolInject();
        inject.setMode(mode);
        inject.setTopK(topK);
        inject.setMinScore(minScore);
        inject.setFallbackFull(fallbackFull);
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        react.setToolInject(inject);
        AgentExecutionProperties p = new AgentExecutionProperties();
        p.setReact(react);
        return p;
    }

    private ToolRetrievalService service(
            AgentExecutionProperties props,
            ToolSetResolver resolver,
            ToolCatalogService catalog,
            AgentSandboxProperties sandbox) {
        return new ToolRetrievalService(mock(ToolRetrievalClient.class), catalog, resolver, sandbox, props);
    }

    private ToolSetResolver resolver(List<String> defaultTools) {
        ToolSetResolver r = mock(ToolSetResolver.class);
        when(r.resolveDefaultTools("t1", null)).thenReturn(defaultTools);
        when(r.resolveDefaultTools("t1", "chat")).thenReturn(defaultTools);
        return r;
    }

    private ToolCatalogService catalog() {
        ToolCatalogService c = mock(ToolCatalogService.class);
        when(c.requiresConfirmation("hr__sensitive")).thenReturn(true);
        when(c.requiresConfirmation("finance__expense")).thenReturn(false);
        when(c.find("finance__expense")).thenReturn(Optional.of(
                new ToolCatalogEntry("finance__expense", "报销提交", "提交报销单并跟踪审批状态", "sdk",
                        "tool-service", null, null, null, null, null, false, true, true, null)));
        when(c.find("hr__sensitive")).thenReturn(Optional.of(
                new ToolCatalogEntry("hr__sensitive", "敏感信息", "读取员工敏感数据（需确认）", "sdk",
                        "tool-service", null, null, null, null, null, true, true, true, null)));
        when(c.find("unknown_tool")).thenReturn(Optional.empty());
        return c;
    }

    @Test
    void retrievalEnabled_checksMode() {
        assertThat(service(props("full", 8, 0.3f, true), resolver(List.of()), catalog(), mock(AgentSandboxProperties.class))
                .retrievalEnabled()).isFalse();
        assertThat(service(props("retrieval", 8, 0.3f, true), resolver(List.of()), catalog(), mock(AgentSandboxProperties.class))
                .retrievalEnabled()).isTrue();
    }

    @Test
    void isAlwaysInject_builtinSandboxHitl() {
        AgentSandboxProperties sandbox = mock(AgentSandboxProperties.class);
        when(sandbox.isSandboxTool("sandbox__exec")).thenReturn(true);
        ToolRetrievalService s = service(props("retrieval", 8, 0.3f, true), resolver(List.of()), catalog(), sandbox);
        assertThat(s.isAlwaysInject("spawn_subagent")).isTrue();
        assertThat(s.isAlwaysInject("sandbox__exec")).isTrue();
        assertThat(s.isAlwaysInject("hr__sensitive")).isTrue();
        assertThat(s.isAlwaysInject("finance__expense")).isFalse();
        assertThat(s.isAlwaysInject("unknown_tool")).isFalse();
    }

    @Test
    void searchableToolIds_excludesAlwaysInject() {
        AgentSandboxProperties sandbox = mock(AgentSandboxProperties.class);
        when(sandbox.isSandboxTool("sandbox__exec")).thenReturn(true);
        ToolRetrievalService s = service(
                props("retrieval", 8, 0.3f, true),
                resolver(List.of("finance__expense", "hr__sensitive", "sandbox__exec", "spawn_subagent")),
                catalog(), sandbox);
        assertThat(s.searchableToolIds("t1", "chat")).containsExactly("finance__expense");
    }

    @Test
    void groupOf_usesToolPrefix() {
        assertThat(ToolRetrievalService.groupOf("finance__expense")).isEqualTo("tool:finance__expense");
    }

    @Test
    void searchToolIds_convergesToSearchablePool_preservesOrder() {
        ToolRetrievalService s = service(
                props("retrieval", 8, 0.3f, true),
                resolver(List.of("finance__expense", "hr__sensitive", "oa__leave")),
                catalog(), mock(AgentSandboxProperties.class));
        ToolRetrievalClient client = mock(ToolRetrievalClient.class);
        when(client.search("报销", 8, "t1")).thenReturn(reactor.core.publisher.Mono.just(List.of(
                new ToolRetrievalClient.ToolIndexHit("oa__leave", 0.92f),
                new ToolRetrievalClient.ToolIndexHit("hr__sensitive", 0.88f),
                new ToolRetrievalClient.ToolIndexHit("finance__expense", 0.7f))));
        ToolRetrievalService svc = new ToolRetrievalService(
                client, catalog(), resolver(List.of("finance__expense", "hr__sensitive", "oa__leave")),
                mock(AgentSandboxProperties.class), props("retrieval", 8, 0.3f, true));
        List<String> ids = svc.searchToolIds("报销", "t1", "chat", 8);
        // 命中去重；hr__sensitive 恒注入被剔除；按相似度保序
        assertThat(ids).containsExactly("oa__leave", "finance__expense");
    }

    @Test
    void searchToolIds_emptyHits_returnsEmpty() {
        ToolRetrievalService s = service(
                props("retrieval", 8, 0.3f, true),
                resolver(List.of("finance__expense")),
                catalog(), mock(AgentSandboxProperties.class));
        ToolRetrievalClient client = mock(ToolRetrievalClient.class);
        when(client.search("报销", 8, "t1")).thenReturn(reactor.core.publisher.Mono.just(List.of()));
        ToolRetrievalService svc = new ToolRetrievalService(
                client, catalog(), resolver(List.of("finance__expense")),
                mock(AgentSandboxProperties.class), props("retrieval", 8, 0.3f, true));
        assertThat(svc.searchToolIds("报销", "t1", "chat", 8)).isEmpty();
    }

    @Test
    void fallbackToolIds_returnsAllSearchable() {
        ToolRetrievalService s = service(
                props("retrieval", 8, 0.3f, true),
                resolver(List.of("finance__expense", "hr__sensitive")),
                catalog(), mock(AgentSandboxProperties.class));
        assertThat(s.fallbackToolIds("t1", "chat")).containsExactly("finance__expense");
    }

    @Test
    void renderToolDirectory_deterministicSortedWithEntryMeta() {
        ToolRetrievalService s = service(
                props("retrieval", 8, 0.3f, true),
                resolver(List.of("finance__expense", "hr__sensitive")),
                catalog(), mock(AgentSandboxProperties.class));
        String dir = s.renderToolDirectory("t1", "chat");
        assertThat(dir)
                .contains("- **finance__expense** 报销提交：提交报销单并跟踪审批状态")
                .contains("- **hr__sensitive** 敏感信息：读取员工敏感数据（需确认）");
        // id 字典序稳定
        assertThat(dir.indexOf("finance__expense")).isLessThan(dir.indexOf("hr__sensitive"));
    }

    @Test
    void paramsSummary_whitelistScalarNoFullPayload() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "required", List.of("amount"),
                "properties", Map.of(
                        "amount", Map.of("type", "number", "description", "报销金额，单位元"),
                        "reason", Map.of("type", "string", "description", "报销事由说明"),
                        "notes", Map.of("type", "string", "description", "备注")));
        String summary = ToolRetrievalService.paramsSummary(parameters);
        assertThat(summary)
                .contains("amount(number) 必填")
                .contains("reason(string)")
                .doesNotContain("type=object");
        assertThat(ToolRetrievalService.paramsSummary(Map.of())).isEmpty();
        assertThat(ToolRetrievalService.paramsSummary(null)).isEmpty();
    }
}
