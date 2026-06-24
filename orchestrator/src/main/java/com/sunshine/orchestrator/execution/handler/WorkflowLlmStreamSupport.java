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

/** Workflow LLM 流式：llm 节点 reasoning → step_delta；answer 节点仅 content → 消息正文 */
final class WorkflowLlmStreamSupport {

    private WorkflowLlmStreamSupport() {
    }

    static Flux<StreamToken> streamTokens(
            LlmGatewayClient llmGateway,
            NodeSpec spec,
            WorkflowContext ctx,
            ExecutionStreamContext streamCtx,
            String nodeId) {
        return streamTokens(llmGateway, spec, ctx, streamCtx, nodeId, false, "");
    }

    static Flux<StreamToken> streamTokens(
            LlmGatewayClient llmGateway,
            NodeSpec spec,
            WorkflowContext ctx,
            ExecutionStreamContext streamCtx,
            String nodeId,
            boolean terminalAnswer,
            String answerOverlay) {
        PromptComposeRequest request = buildRequest(spec, ctx, streamCtx, terminalAnswer, answerOverlay);
        String stepId = WorkflowNodeTimeline.stepId(nodeId);
        return llmGateway.streamComposed(request)
                .concatMap(token -> mapStreamToken(token, stepId, terminalAnswer));
    }

    static NodeResult buildResult(WorkflowStreamCollector collector) {
        return buildResult(collector, false);
    }

    static NodeResult buildResult(WorkflowStreamCollector collector, boolean terminalAnswer) {
        String text = collector.content();
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", text);
        outputs.put("output", text);
        if (terminalAnswer) {
            outputs.put("detail", text.isBlank() ? "未生成内容" : text.strip());
            return NodeResult.ok(outputs);
        }
        String reasoning = collector.reasoning();
        if (StringUtils.hasText(reasoning)) {
            outputs.put("reasoning", reasoning.strip());
            outputs.put("detail", reasoning.strip());
        } else {
            outputs.put("detail", text.isBlank() ? "未生成内容" : text.strip());
        }
        return NodeResult.ok(outputs);
    }

    static Flux<StreamToken> mapStreamToken(StreamToken token, String stepId, boolean terminalAnswer) {
        if (token.isReasoning()) {
            if (terminalAnswer) {
                return Flux.empty();
            }
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
        return buildRequest(spec, ctx, streamCtx, false, "");
    }

    static PromptComposeRequest buildRequest(
            NodeSpec spec,
            WorkflowContext ctx,
            ExecutionStreamContext streamCtx,
            boolean terminalAnswer,
            String answerOverlay) {
        String nodePrompt = spec.params().getOrDefault("prompt", "");
        if (terminalAnswer && StringUtils.hasText(answerOverlay)) {
            nodePrompt = answerOverlay.strip() + "\n\n" + nodePrompt;
        }
        String userQuery = ctx.resolvePath("start.userQuery");
        if (!StringUtils.hasText(userQuery)) {
            userQuery = streamCtx.userContent();
        }
        ExecutionPlan plan = streamCtx.plan();
        String workflowId = plan != null ? plan.workflowId() : null;
        MemoryContext memory = terminalAnswer
                ? MemoryContext.empty()
                : (streamCtx.memory() != null ? streamCtx.memory() : MemoryContext.empty());
        return PromptComposeRequest.forWorkflowLlm(workflowId, memory, userQuery, nodePrompt);
    }

    static boolean hasNodePrompt(NodeSpec spec) {
        return spec.params() != null && StringUtils.hasText(spec.params().get("prompt"));
    }
}
