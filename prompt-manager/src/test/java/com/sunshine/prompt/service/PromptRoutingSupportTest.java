package com.sunshine.prompt.service;

import com.sunshine.prompt.dto.RoutingDryRunRequest;
import com.sunshine.prompt.dto.RoutingDryRunResponse;
import com.sunshine.prompt.dto.RoutingRuleInput;
import com.sunshine.prompt.dto.RoutingValidateRequest;
import com.sunshine.prompt.dto.RoutingValidateResponse;
import com.sunshine.prompt.entity.PromptDefinitionEntity;
import com.sunshine.prompt.entity.PromptVersionEntity;
import com.sunshine.prompt.repo.PromptDefinitionRepository;
import com.sunshine.prompt.repo.PromptVersionRepository;
import com.sunshine.routing.RoutingDryRunResult;
import com.sunshine.routing.RoutingRuleDef;
import com.sunshine.routing.UnifiedRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptRoutingSupportTest {
    private static final String STRUCTURAL_JSON = """
            {"matchType":"structural","minDomainGroups":2,"patterns":["先.+再","再.+(并|然后|接着)","分步","多步","并对.+?(分析|审查|检查|评估)","完整处理","一套.+(分析|流程|处理)"],"domainGroups":{"knowledge":["制度","检索","知识库","政策","差旅办法","报销规定"],"finance":["待审批","报销","财务","付款","单据"],"analysis":["合规","分析","审查","对比","评估","结论"]},"plan":{"mode":"plan-workflow","params":{}}}""";
    private static final String FINANCE_LIST_JSON = """
            {"matchType":"regex","match":"any","patterns":["有哪些待审批","查询待审批","列出待审批","待审批的.*报销","待审批.*付款"],"plan":{"mode":"workflow","workflowId":"finance-list","params":{"status":"pending"}}}""";

    @Mock
    private PromptDefinitionRepository definitionRepository;
    @Mock
    private PromptVersionRepository versionRepository;
    @InjectMocks
    private PromptRoutingSupport routingSupport;
    private PromptRoutingService routingService;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-20T03:00:00Z");
        routingService = new PromptRoutingService(routingSupport);
    }

    @Test
    void parse_usesFullPromptIdAsRuleId() {
        RoutingRuleDef def = routingSupport.parse(
                "routing-rule.structural-plan", 100, true, STRUCTURAL_JSON);
        assertThat(def.id()).isEqualTo("routing-rule.structural-plan");
        assertThat(def.matchType()).isEqualTo("structural");
        assertThat(def.minDomainGroups()).isEqualTo(2);
        assertThat(def.plan().mode()).isEqualTo("plan-workflow");
        assertThat(def.domainGroups()).containsKeys("knowledge", "finance", "analysis");
    }

    @Test
    void dryRunHitsStructural() {
        List<RoutingRuleDef> rules = t0Rules();
        RoutingDryRunResult result = RoutingDryRunResult.dryRun("先检索制度再分析报销合规", rules);
        assertThat(result.matchedRuleId()).isEqualTo("routing-rule.structural-plan");
        assertThat(result.wouldLlm()).isFalse();
        assertThat(result.stage()).isEqualTo("rule-engine");
    }

    @Test
    void validateDetectsSamePriority() {
        RoutingValidateResponse resp = routingService.validate(new RoutingValidateRequest(
                List.of(
                        new RoutingRuleInput("routing-rule.a", 20, true, FINANCE_LIST_JSON),
                        new RoutingRuleInput("routing-rule.b", 20, true, FINANCE_LIST_JSON))));
        assertThat(resp.warnings()).isNotEmpty();
        assertThat(resp.warnings().get(0).message()).contains("same priority 20");
    }

    @Test
    void dryRunService_returnsPlanFromDbRules() {
        stubT0DbRules();
        RoutingDryRunResponse resp = routingService.dryRun(new RoutingDryRunRequest(
                "先检索制度再分析报销合规", false));
        assertThat(resp.matchedRuleId()).isEqualTo("routing-rule.structural-plan");
        assertThat(resp.wouldLlm()).isFalse();
        assertThat(resp.stage()).isEqualTo("rule-engine");
        assertThat(resp.plan().mode()).isEqualTo("plan-workflow");
    }

    @Test
    void dryRunWouldLlmWhenNoMatch() {
        stubT0DbRules();
        RoutingDryRunResponse resp = routingService.dryRun(new RoutingDryRunRequest("随便聊聊", false));
        assertThat(resp.matchedRuleId()).isNull();
        assertThat(resp.wouldLlm()).isTrue();
        assertThat(resp.stage()).isEqualTo("would_llm");
        assertThat(resp.plan()).isNull();
    }

    @Test
    void structuralBeatsLowerPriorityRegex() {
        List<RoutingRuleDef> rules = t0Rules();
        var hit = new UnifiedRuleEngine(rules).match("先检索制度再分析报销待审批");
        assertThat(hit).isPresent();
        assertThat(hit.get().ruleId()).isEqualTo("routing-rule.structural-plan");
    }

    private List<RoutingRuleDef> t0Rules() {
        return List.of(
                routingSupport.parse("routing-rule.structural-plan", 100, true, STRUCTURAL_JSON),
                routingSupport.parse("routing-rule.rule-finance-list-pending", 10, true, FINANCE_LIST_JSON));
    }

    private void stubT0DbRules() {
        when(definitionRepository.findByKindAndEnabled(PromptRoutingSupport.ROUTING_RULE_KIND, true))
                .thenReturn(List.of(
                        routingDef("routing-rule.structural-plan", 100),
                        routingDef("routing-rule.rule-finance-list-pending", 10)));
        when(versionRepository.findByPromptIdAndVersion("routing-rule.structural-plan", 1))
                .thenReturn(Optional.of(published("routing-rule.structural-plan", STRUCTURAL_JSON)));
        when(versionRepository.findByPromptIdAndVersion("routing-rule.rule-finance-list-pending", 1))
                .thenReturn(Optional.of(published("routing-rule.rule-finance-list-pending", FINANCE_LIST_JSON)));
    }

    private PromptDefinitionEntity routingDef(String id, int priority) {
        PromptDefinitionEntity d = new PromptDefinitionEntity();
        d.setId(id);
        d.setKind(PromptRoutingSupport.ROUTING_RULE_KIND);
        d.setDisplayName(id);
        d.setEnabled(true);
        d.setPriority(priority);
        d.setActiveVersion(1);
        d.setCatalogVersion(1L);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    private PromptVersionEntity published(String promptId, String json) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setPromptId(promptId);
        v.setVersion(1);
        v.setStatus("published");
        v.setContentJson(json);
        v.setCreatedAt(now);
        return v;
    }
}
