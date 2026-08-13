package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    @Test
    void buildClassifierUserMessage_includesContextAndCurrentQuery() {
        AssembledContext memory = new AssembledContext("", "用户此前在讨论沙箱脚本", List.of(), List.of(
                        new ChatTurn("user", "上一个问题"),
                        new ChatTurn("assistant", "上一个回答")), "");
        RoutingContext ctx = new RoutingContext(
                "看一下这个skills能做什么，分析一下脚本",
                "msg-1",
                null,
                null,
                "sandbox-coding-demo",
                memory);

        String content = IntentRouter.buildClassifierUserMessage(ctx);

        assertThat(content).contains("UI 已选 Skill: sandbox-coding-demo");
        assertThat(content).contains("用户此前在讨论沙箱脚本");
        assertThat(content).contains("user: 上一个问题");
        assertThat(content).contains("【当前问题】");
        assertThat(content).contains("看一下这个skills能做什么");
    }

    @Test
    void buildClassifierUserMessage_minimalWhenNoMemory() {
        RoutingContext ctx = new RoutingContext("随便聊聊", null);
        String content = IntentRouter.buildClassifierUserMessage(ctx);
        assertThat(content).isEqualTo("【当前问题】\n随便聊聊");
    }

    @Test
    void buildClassifierUserMessage_includesLockedMode() {
        RoutingContext ctx = new RoutingContext("制度怎么说", null)
                .withLockedMode(ExecutionMode.FAST);
        String content = IntentRouter.buildClassifierUserMessage(ctx);
        assertThat(content).contains("【模式锁定·轨A】");
        assertThat(content).contains("fast");
        assertThat(content).contains("【当前问题】");
    }

    @Test
    void applyLockedMode_overridesModeAndStripsCrossTrackBindings() {
        ExecutionPlan llm = new ExecutionPlan(
                ExecutionMode.WORKFLOW,
                "finance-smart",
                Map.of("reactPromptId", "react-prompt.policy-qa", "skill", "x"),
                "llm");
        ExecutionPlan locked = IntentRouter.applyLockedMode(llm, ExecutionMode.FAST);
        assertThat(locked.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(locked.workflowId()).isNull();
        assertThat(locked.params()).containsEntry("reactPromptId", "react-prompt.policy-qa");
        assertThat(locked.params()).containsEntry("skill", "x");
    }

    @Test
    void applyLockedMode_workflowTrack_stripsSkillAndAgent() {
        ExecutionPlan llm = new ExecutionPlan(
                ExecutionMode.FAST,
                "knowledge-qa",
                Map.of("skill", "x", "agentIds", "a1", "status", "pending"),
                "llm");
        ExecutionPlan locked = IntentRouter.applyLockedMode(llm, ExecutionMode.WORKFLOW);
        assertThat(locked.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(locked.workflowId()).isEqualTo("knowledge-qa");
        assertThat(locked.params()).containsEntry("status", "pending");
        assertThat(locked.params()).doesNotContainKey("skill");
        assertThat(locked.params()).doesNotContainKey("agentIds");
    }

    @Test
    void buildClassifierUserMessage_trackAForbidsModeFields() {
        RoutingContext ctx = new RoutingContext("制度怎么说", null)
                .withLockedMode(ExecutionMode.FAST);
        String content = IntentRouter.buildClassifierUserMessage(ctx);
        assertThat(content).contains("轨A");
        assertThat(content).contains("禁止输出 executionMode、planMode");
    }
}
