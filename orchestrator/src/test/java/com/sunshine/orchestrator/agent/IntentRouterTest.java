package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    @Test
    void buildClassifierUserMessage_includesContextAndCurrentQuery() {
        MemoryContext memory = new MemoryContext(
                "",
                "用户此前在讨论沙箱脚本",
                List.of(
                        new ChatTurn("user", "上一个问题"),
                        new ChatTurn("assistant", "上一个回答")));
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
}
