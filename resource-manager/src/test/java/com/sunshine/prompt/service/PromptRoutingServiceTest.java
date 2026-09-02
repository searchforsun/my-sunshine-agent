package com.sunshine.prompt.service;

import com.sunshine.prompt.dto.RoutingDryRunRequest;
import com.sunshine.prompt.dto.RoutingDryRunResponse;
import com.sunshine.routing.RoutingPlanSpec;
import com.sunshine.routing.RoutingRuleDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptRoutingServiceTest {

    private PromptRoutingService service;

    @BeforeEach
    void setUp() {
        PromptRoutingSupport support = mock(PromptRoutingSupport.class);
        service = new PromptRoutingService(support);
        when(support.loadEnabledRules()).thenReturn(List.of(
                rule("rule-fast", 10, "fast", "差旅"),
                rule("rule-wf", 30, "workflow", "年假")));
    }

    private static RoutingRuleDef rule(String id, int priority, String mode, String pattern) {
        RoutingPlanSpec plan = "workflow".equals(mode)
                ? new RoutingPlanSpec("workflow", "knowledge-qa", Map.of())
                : new RoutingPlanSpec(mode, null, Map.of());
        return new RoutingRuleDef(id, priority, true, "regex", "any",
                List.of(pattern), Map.of(), 2, plan);
    }

    @Test
    void dryRun_fast_hitsTrackARule() {
        RoutingDryRunResponse resp = service.dryRun(new RoutingDryRunRequest("差旅办法怎么说", false, "fast"));
        assertThat(resp.stage()).isEqualTo("rule");
        assertThat(resp.matchedRuleId()).isEqualTo("rule-fast");
        assertThat(resp.plan().mode()).isEqualTo("fast");
    }

    @Test
    void dryRun_pro_sharesTrackARule() {
        RoutingDryRunResponse resp = service.dryRun(new RoutingDryRunRequest("差旅办法怎么说", false, "pro"));
        assertThat(resp.stage()).isEqualTo("rule");
        assertThat(resp.matchedRuleId()).isEqualTo("rule-fast");
        assertThat(resp.plan().mode()).isEqualTo("fast");
    }

    @Test
    void dryRun_workflow_hitsWorkflowRule() {
        RoutingDryRunResponse resp = service.dryRun(new RoutingDryRunRequest("年假制度", false, "workflow"));
        assertThat(resp.stage()).isEqualTo("rule");
        assertThat(resp.matchedRuleId()).isEqualTo("rule-wf");
        assertThat(resp.plan().workflowId()).isEqualTo("knowledge-qa");
    }

    @Test
    void dryRun_missingModeDefaultsToFast() {
        RoutingDryRunResponse resp = service.dryRun(new RoutingDryRunRequest("差旅办法怎么说", false));
        assertThat(resp.stage()).isEqualTo("rule");
        assertThat(resp.matchedRuleId()).isEqualTo("rule-fast");
    }
}
