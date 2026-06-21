package com.sunshine.orchestrator.routing;



import com.sunshine.orchestrator.agent.IntentRouter;

import com.sunshine.orchestrator.rewrite.QueryRewriteService;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;

import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;



import java.util.Map;

import java.util.Optional;



import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)

class ExecutionPlanRouterTest {



    @Mock

    private RuleBasedRouter ruleBasedRouter;

    @Mock

    private IntentRouter intentRouter;

    @Mock

    private QueryRewriteService queryRewriteService;



    @InjectMocks

    private ExecutionPlanRouter router;



    @Test

    void ruleHitSkipsIntentRewrite() {

        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "rule");

        when(ruleBasedRouter.match("年假几天")).thenReturn(Optional.of(plan));

        assertThat(router.route("年假几天").block()).isEqualTo(plan);

        verify(queryRewriteService, never()).rewriteForIntent(org.mockito.ArgumentMatchers.anyString());

        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.anyString());

    }



    @Test

    void shortQueryUsesIntentRewriteBeforeClassify() {

        when(ruleBasedRouter.match("待审批")).thenReturn(Optional.empty());

        when(queryRewriteService.shouldRewriteIntent("待审批")).thenReturn(true);

        when(queryRewriteService.rewriteForIntent("待审批")).thenReturn("查询待审批报销消息");

        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of(), "llm");

        when(intentRouter.classifyPlan("查询待审批报销消息")).thenReturn(Mono.just(plan));

        assertThat(router.route("待审批").block()).isEqualTo(plan);

        verify(intentRouter).classifyPlan(eq("查询待审批报销消息"));

    }



    @Test

    void longQuerySkipsIntentRewrite() {

        String query = "年假可以请几天";

        when(ruleBasedRouter.match(query)).thenReturn(Optional.empty());

        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);

        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm");

        when(intentRouter.classifyPlan(query)).thenReturn(Mono.just(plan));

        assertThat(router.route(query).block()).isEqualTo(plan);

        verify(queryRewriteService, never()).rewriteForIntent(org.mockito.ArgumentMatchers.anyString());

    }

}


