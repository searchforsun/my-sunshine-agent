package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    @Test
    void replacesNodeField() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("start", Map.of("userQuery", "你好"));
        String out = TemplateResolver.resolve("问题：{{start.userQuery}}", ctx);
        assertThat(out).isEqualTo("问题：你好");
    }

    @Test
    void replacesHyphenatedNodeField() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("finance-list", Map.of("output", "共 3 条财务消息"));
        assertThat(TemplateResolver.resolve("数据：{{finance-list.output}}", ctx))
                .isEqualTo("数据：共 3 条财务消息");
    }
}
