package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;

import java.util.ArrayList;
import java.util.List;

/** 子 Agent 流式聚合：正文 + 子 Timeline 步骤 */
public final class AgentStreamCollector extends WorkflowStreamCollector {

    private final SubAgentTimelineBridge bridge;
    private final String skillId;
    private final StringBuilder content = new StringBuilder();
    private final List<String> toolCalls = new ArrayList<>();
    private NodeSpec auditSpec;
    private ExecutionStreamContext auditStreamCtx;
    private com.sunshine.orchestrator.agent.runtime.AgentRunRequest auditRequest;

    public AgentStreamCollector(String nodeId, String nodeLabel, String skillId) {
        this.bridge = new SubAgentTimelineBridge(nodeId, nodeLabel);
        this.skillId = skillId;
    }

    /** 原始 sub-agent token → node 步骤更新（子步骤不上主 Timeline 顶层） */
    public List<StreamToken> ingest(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        if (token.isContent() && token.text() != null) {
            content.append(token.text());
            return List.of();
        }
        if (token.isStep() && token.step() != null && token.step().id() != null
                && token.step().id().startsWith("tool-")) {
            toolCalls.add(token.step().id());
        }
        return bridge.wrap(token);
    }

    @Override
    public String content() {
        return content.toString();
    }

    public List<ProcessingStep> subSteps() {
        return bridge.subSteps();
    }

    public List<String> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public String skillId() {
        return skillId;
    }

    public void bindAuditContext(
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            com.sunshine.orchestrator.agent.runtime.AgentRunRequest request) {
        this.auditSpec = spec;
        this.auditStreamCtx = streamCtx;
        this.auditRequest = request;
    }

    public NodeSpec auditSpec() {
        return auditSpec;
    }

    public ExecutionStreamContext auditStreamCtx() {
        return auditStreamCtx;
    }

    public com.sunshine.orchestrator.agent.runtime.AgentRunRequest auditRequest() {
        return auditRequest;
    }
}
