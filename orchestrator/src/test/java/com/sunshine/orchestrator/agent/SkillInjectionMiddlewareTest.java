package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 技能正文 SYSTEM 注入中间件单测：
 * 仅 MAIN 注入 / 触发集去重拼接正文 / <skill_information> 指令信封 / 非 MAIN 与空触发集不注入。
 */
class SkillInjectionMiddlewareTest {

    private SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
    private SkillInjectionMiddleware middleware = new SkillInjectionMiddleware(skillCatalogService);

    private RuntimeContext ctx(AgentRole role, List<String> triggeredSkillIds) {
        RuntimeContext rt = RuntimeContext.builder()
                .put(ProcessingStepMiddleware.CTX_AGENT_ROLE, role)
                .put(SkillInjectionMiddleware.CTX_TRIGGERED_SKILL_IDS, triggeredSkillIds)
                .build();
        return rt;
    }

    @Test
    void main_appendsSkillOverlayToSystemPrompt() {
        when(skillCatalogService.overlayOrEmpty("brainstorming")).thenReturn("HARD-GATE 正文");

        String result = middleware.onSystemPrompt(mock(Agent.class), ctx(AgentRole.MAIN, List.of("brainstorming")), "base")
                .block();

        assertThat(result).startsWith("base");
        assertThat(result).contains("<skill_information>");
        assertThat(result).contains("<skills_referenced>");
        assertThat(result).contains("- brainstorming");
        assertThat(result).contains("<skill_block>");
        assertThat(result).contains("HARD-GATE 正文");
    }

    @Test
    void main_dedupsAndJoinsMultipleTriggeredSkills() {
        when(skillCatalogService.overlayOrEmpty("skill-a")).thenReturn("OVERLAY-A");
        when(skillCatalogService.overlayOrEmpty("skill-b")).thenReturn("OVERLAY-B");

        RuntimeContext rt = ctx(AgentRole.MAIN, List.of("skill-a", "skill-b", "skill-a"));
        String result = middleware.onSystemPrompt(mock(Agent.class), rt, "base").block();

        assertThat(result).contains("OVERLAY-A");
        assertThat(result).contains("OVERLAY-B");
        // 去重：skill-a 只在索引出现一次（skill_block 正文不含 id）
        assertThat(result).containsOnlyOnce("- skill-a");
        // 去重后 overlayOrEmpty 对 skill-a 只取一次
        org.mockito.Mockito.verify(skillCatalogService, org.mockito.Mockito.times(1))
                .overlayOrEmpty("skill-a");
    }

    @Test
    void nonMain_doesNotInject() {
        when(skillCatalogService.overlayOrEmpty("skill-a")).thenReturn("OVERLAY-A");

        String result = middleware.onSystemPrompt(mock(Agent.class),
                ctx(AgentRole.SUB, List.of("skill-a")), "base").block();

        assertThat(result).isEqualTo("base");
    }

    @Test
    void main_noTriggeredSkills_doesNotInject() {
        String result = middleware.onSystemPrompt(mock(Agent.class), ctx(AgentRole.MAIN, List.of()), "base").block();

        assertThat(result).isEqualTo("base");
    }

    @Test
    void main_emptyCatalogOverlay_doesNotInject() {
        when(skillCatalogService.overlayOrEmpty("skill-a")).thenReturn("");

        String result = middleware.onSystemPrompt(mock(Agent.class),
                ctx(AgentRole.MAIN, List.of("skill-a")), "base").block();

        assertThat(result).isEqualTo("base");
    }
}
