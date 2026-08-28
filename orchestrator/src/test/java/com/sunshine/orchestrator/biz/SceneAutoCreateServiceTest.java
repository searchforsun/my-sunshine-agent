package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 自动场景创建（authority §5.5b）单测：
 * 触发闸门（≥2 轮 / auto 上限 / 频率限制）· 同义重复抑制复用 · LLM 创建/跳过/非法格式。
 */
class SceneAutoCreateServiceTest {

    private static final String TEMPLATE =
            "你是业务场景识别器。\n=== USER ===\n{conversation}";

    private BusinessContextProperties properties;
    private BizSceneCatalogClient catalogClient;
    private SceneEmbeddingService embeddingService;
    private LlmGatewayClient llmGatewayClient;
    private PromptCatalogHolder catalogHolder;
    private SceneAutoCreateService service;

    @BeforeEach
    void setUp() {
        properties = new BusinessContextProperties();
        properties.setEnabled(true);
        properties.getSceneAuto().setEnabled(true);
        catalogClient = mock(BizSceneCatalogClient.class);
        embeddingService = mock(SceneEmbeddingService.class);
        llmGatewayClient = mock(LlmGatewayClient.class);
        catalogHolder = mock(PromptCatalogHolder.class);
        when(catalogHolder.snapshot()).thenReturn(PromptCatalogSnapshot.of(0, List.of(
                new PromptCatalogEntry("context.biz-scene.auto-create", "context", "auto-create",
                        true, 0, 1, TEMPLATE, null))));
        service = new SceneAutoCreateService(
                properties, catalogClient, embeddingService, llmGatewayClient, catalogHolder);
        when(embeddingService.index()).thenReturn(List.of());
        when(embeddingService.searchClosestAnyStatus(anyString()))
                .thenReturn(java.util.Optional.empty());
        when(embeddingService.embed(anyString())).thenReturn(null);
    }

    private static List<String> turns(String... bodies) {
        return List.of(bodies);
    }

    @Test
    void fewerThan2UserTurns_returnsEmpty() {
        assertThat(service.tryCreate("t1", "conv-1", turns("你好"), turns("你好呀"))).isEmpty();
        verify(catalogClient, never()).createAuto(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void gateOff_returnsEmpty() {
        properties.getSceneAuto().setEnabled(false);
        assertThat(service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交"))).isEmpty();
    }

    @Test
    void maxPendingReached_returnsEmpty() {
        List<BizSceneCatalogClient.SceneIndexEntry> full = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new BizSceneCatalogClient.SceneIndexEntry(
                        "auto-" + i, "描述", List.of(1f), "pending_review", "auto", "default"))
                .toList();
        when(embeddingService.index()).thenReturn(full);
        assertThat(service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交"))).isEmpty();
    }

    @Test
    void rateLimitExceeded_returnsEmpty() {
        properties.getSceneAuto().setCreateRateLimit(1);
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"scene\":\"refund-inquiry\",\"display_name\":\"退款咨询\","
                        + "\"description\":\"用户咨询退款进度与原因的常见场景\"}");
        String first = service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交")).orElse(null);
        assertThat(first).isEqualTo("refund-inquiry");
        assertThat(service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交"))).isEmpty();
    }

    @Test
    void duplicateSimilarity_reusesExisting() {
        when(embeddingService.searchClosestAnyStatus(anyString()))
                .thenReturn(java.util.Optional.of(
                        new SceneEmbeddingService.SceneMatch("expense-assist", 0.9)));
        assertThat(service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交")))
                .contains("expense-assist");
        verify(llmGatewayClient, never()).complete(anyString(), anyString());
        verify(catalogClient, never()).createAuto(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void llmJudgesSkip_returnsEmpty() {
        when(llmGatewayClient.complete(anyString(), anyString())).thenReturn("{\"skip\":true}");
        assertThat(service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交"))).isEmpty();
    }

    @Test
    void llmCreatesScene_createsAutoAndReturnsCode() {
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"scene\":\"overtime-apply\",\"display_name\":\"加班申请\","
                        + "\"description\":\"员工加班申请与审批进度查询场景\"}");
        assertThat(service.tryCreate("t1", "conv-1",
                turns("加班怎么申请", "审批到哪了"), turns("我来帮您", "审批中"))).contains("overtime-apply");
        verify(catalogClient).createAuto("overtime-apply", "加班申请",
                "员工加班申请与审批进度查询场景", "conv-1");
    }

    @Test
    void llmInvalidFormat_returnsEmpty() {
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"scene\":\"Bad Code\",\"display_name\":\"\",\"description\":\"\"}");
        assertThat(service.tryCreate("t1", "conv-1",
                turns("报销怎么提交", "单据丢了"), turns("我来帮您", "请重新提交"))).isEmpty();
        verify(catalogClient, never()).createAuto(anyString(), anyString(), anyString(), anyString());
    }
}
