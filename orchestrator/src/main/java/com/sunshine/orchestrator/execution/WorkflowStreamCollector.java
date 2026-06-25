package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;

/** 聚合 Workflow 流式节点 token，供 finalize 写入上下文 */
public class WorkflowStreamCollector {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();

    public void accept(StreamToken token) {
        if (token == null) {
            return;
        }
        if (token.isContent() && token.text() != null) {
            content.append(token.text());
        } else if (token.isStepDelta() && "reasoning".equals(token.channel()) && token.text() != null) {
            reasoning.append(token.text());
        } else if (token.isReasoning() && token.text() != null) {
            reasoning.append(token.text());
        }
    }

    public String content() {
        return content.toString();
    }

    public String reasoning() {
        return reasoning.toString();
    }
}
