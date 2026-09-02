package com.sunshine.orchestrator.biz;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.sunshine.orchestrator.biz.BizSceneResolver.SceneTagged;
import static org.assertj.core.api.Assertions.assertThat;

class BizSceneResolverTest {

    private static final Set<String> ACTIVE = Set.of("compliance-review", "policy-qa");

    @Test
    void agentSceneTakesPriorityOverSkillScene() {
        List<SceneTagged> agents = List.of(new SceneTagged("finance-agent", "compliance-review"));
        List<SceneTagged> skills = List.of(new SceneTagged("finance-skill", "policy-qa"));
        assertThat(BizSceneResolver.resolve(agents, skills, ACTIVE))
                .contains("compliance-review");
    }

    @Test
    void skipsEmptyAgents_thenTakesFirstNonEmptySkill() {
        List<SceneTagged> agents = List.of(new SceneTagged("a1", "  "), new SceneTagged("a2", null));
        List<SceneTagged> skills = List.of(new SceneTagged("s1", null), new SceneTagged("s2", "policy-qa"));
        assertThat(BizSceneResolver.resolve(agents, skills, ACTIVE)).contains("policy-qa");
    }

    @Test
    void noScene_returnsEmpty() {
        assertThat(BizSceneResolver.resolve(List.of(), List.of(), ACTIVE)).isEmpty();
        assertThat(BizSceneResolver.resolve(null, List.of(new SceneTagged("s1", null)), ACTIVE)).isEmpty();
    }

    @Test
    void disabledOrUnknownScene_isInvalid() {
        List<SceneTagged> skills = List.of(new SceneTagged("s1", "legacy-scene"));
        assertThat(BizSceneResolver.resolve(List.of(), skills, ACTIVE)).isEmpty();
        assertThat(BizSceneResolver.resolve(List.of(), skills, Set.of())).isEmpty();
    }

    @Test
    void stripsWhitespaceAndMatchesActive() {
        List<SceneTagged> agents = List.of(new SceneTagged("a1", " compliance-review "));
        assertThat(BizSceneResolver.resolve(agents, List.of(), ACTIVE)).contains("compliance-review");
    }

    @Test
    void activeCodesNull_fallsBackToEmpty() {
        List<SceneTagged> skills = List.of(new SceneTagged("s1", "policy-qa"));
        assertThat(BizSceneResolver.resolve(List.of(), skills, null)).isEmpty();
    }
}
