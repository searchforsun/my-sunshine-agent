package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写路径场景回退链（authority §5.5）单测：
 * ① 路由种子优先 · ② embedding 回退 · ③ auto-create 兜底 · 总开关。
 */
class SceneWriteResolverTest {

    private BusinessContextProperties properties;
    private AgentCatalogService agentCatalogService;
    private SkillCatalogService skillCatalogService;
    private BizSceneCatalogClient bizSceneCatalogClient;
    private SceneEmbeddingService embeddingService;
    private SceneAutoCreateService sceneAutoCreateService;
    private SceneWriteResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new BusinessContextProperties();
        properties.setEnabled(true);
        agentCatalogService = mock(AgentCatalogService.class);
        skillCatalogService = mock(SkillCatalogService.class);
        bizSceneCatalogClient = mock(BizSceneCatalogClient.class);
        embeddingService = mock(SceneEmbeddingService.class);
        sceneAutoCreateService = mock(SceneAutoCreateService.class);
        resolver = new SceneWriteResolver(properties, agentCatalogService, skillCatalogService,
                bizSceneCatalogClient, embeddingService, sceneAutoCreateService);
    }

    private static ChatMessageEntity msg(String role, String content, String skillIds, String agentIds) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setRole(role);
        m.setContent(content);
        m.setRoutingSkillIds(skillIds);
        m.setRoutingAgentIds(agentIds);
        return m;
    }

    @Test
    void gateOff_returnsEmpty() {
        properties.setEnabled(false);
        assertThat(resolver.resolve("u1", "t1", "c1",
                List.of(msg("user", "报销怎么提交", null, null)))).isEmpty();
    }

    @Test
    void routingSeed_winsOverEmbedding() {
        ChatMessageEntity m = msg("user", "报销怎么提交", "expense-assist", null);
        // skill 解析为 scene
        when(skillCatalogService.find("expense-assist"))
                .thenReturn(Optional.of(new SkillCatalogEntry(
                        "expense-assist", "报销助手", "报销", "overlay", "[]", 1, true,
                        "off", null, "all", "expense-assist", "default")));
        when(bizSceneCatalogClient.activeCodes())
                .thenReturn(java.util.Set.of("expense-assist", "travel-budget"));

        assertThat(resolver.resolve("u1", "t1", "c1", List.of(m))).contains("expense-assist");
        verify(embeddingService, never()).search(anyString());
        verify(sceneAutoCreateService, never()).tryCreate(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void routingMiss_embeddingFallback() {
        when(embeddingService.enabled()).thenReturn(true);
        when(embeddingService.search(anyString()))
                .thenReturn(Optional.of(new SceneEmbeddingService.SceneMatch("travel-budget", 0.8)));
        assertThat(resolver.resolve("u1", "t1", "c1",
                List.of(msg("user", "出差的酒店额度多少", null, null),
                        msg("assistant", "差旅预算标准是 500 元/晚", null, null))))
                .contains("travel-budget");
        verify(sceneAutoCreateService, never()).tryCreate(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void allMiss_autoCreate() {
        when(embeddingService.enabled()).thenReturn(true);
        when(embeddingService.search(anyString())).thenReturn(Optional.empty());
        when(sceneAutoCreateService.tryCreate(anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Optional.of("overtime-apply"));
        assertThat(resolver.resolve("u1", "t1", "c1",
                List.of(msg("user", "加班怎么申请", null, null),
                        msg("assistant", "我帮您提交加班申请", null, null))))
                .contains("overtime-apply");
    }
}
