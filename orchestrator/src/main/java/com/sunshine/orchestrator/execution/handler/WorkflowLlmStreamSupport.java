package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/** Workflow 终态 LLM 流式：reasoning → 节点 detail，content → 消息区正文 */
final class WorkflowLlmStreamSupport {

    private WorkflowLlmStreamSupport() {
    }

    static Flux<StreamToken> streamTokens(
            LlmGatewayClient llmGateway,
            NodeSpec spec,
            WorkflowContext ctx,
            ExecutionStreamContext streamCtx,
            String nodeId) {
        PromptComposeRequest request = buildRequest(spec, ctx, streamCtx);
        String stepId = WorkflowNodeTimeline.stepId(nodeId);
        return llmGateway.streamComposed(request)
                .concatMap(token -> mapStreamToken(token, stepId));
    }

    static NodeResult buildResult(WorkflowStreamCollector collector) {
        String text = collector.content();
        String reasoning = collector.reasoning();
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", text);
        outputs.put("output", text);
        if (StringUtils.hasText(reasoning)) {
            outputs.put("reasoning", reasoning.strip());
            outputs.put("detail", reasoning.strip());
        } else {
            outputs.put("detail", text.isBlank() ? "未生成内容" : text.strip());
        }
        return NodeResult.ok(outputs);
    }

    static Flux<StreamToken> mapStreamToken(StreamToken token, String stepId) {
        if (token.isReasoning()) {
            String text = token.text();
            if (!StringUtils.hasText(text)) {
                return Flux.empty();
            }
            return Flux.just(StreamToken.stepDelta(stepId, "reasoning", text));
        }
        if (token.isContent()) {
            return Flux.just(token);
        }
        return Flux.empty();
    }

    static PromptComposeRequest buildRequest(
            NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String nodePrompt = spec.params().getOrDefault("prompt", "");
        String userQuery = ctx.resolvePath("start.userQuery");
        if (!StringUtils.hasText(userQuery)) {
            userQuery = streamCtx.userContent();
        }
        ExecutionPlan plan = streamCtx.plan();
        String workflowId = plan != null ? plan.workflowId() : null;
        MemoryContext memory = streamCtx.memory() != null ? streamCtx.memory() : MemoryContext.empty();
        return PromptComposeRequest.forWorkflowLlm(workflowId, memory, userQuery, nodePrompt);
    }

    static boolean hasNodePrompt(NodeSpec spec) {
        return spec.params() != null && StringUtils.hasText(spec.params().get("prompt"));
    }
}
