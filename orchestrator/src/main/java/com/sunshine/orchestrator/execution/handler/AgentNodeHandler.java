package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.agent.AgentNodeDetailSummarizer;
import com.sunshine.orchestrator.execution.agent.AgentNodeOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Agent 子节点 — 黑盒 f(input)→output，由引擎调度 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeHandler implements NodeHandler {

    private final AgentRuntime agentRuntime;

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String query = spec.params().getOrDefault("query", ctx.resolvePath("start.userQuery"));
        List<String> injected = parseInjectedBlocks(spec.params().getOrDefault("context", ""));

        return agentRuntime.run(AgentRunRequest.sub(
                        streamCtx.memory(), query, injected,
                        streamCtx.userId(), streamCtx.tenantId()))
                .collectList()
                .map(tokens -> toNodeResult(tokens))
                .onErrorResume(e -> {
                    log.warn("[AgentNodeHandler] 子 Agent 失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    private static List<String> parseInjectedBlocks(String context) {
        if (context == null || context.isBlank()) {
            return List.of();
        }
        return List.of(context.strip());
    }

    private static NodeResult toNodeResult(List<StreamToken> tokens) {
        List<StreamToken> timeline = tokens.stream()
                .filter(t -> t.isStep() || t.isStepDelta())
                .toList();
        String answer = tokens.stream()
                .filter(StreamToken::isContent)
                .map(StreamToken::text)
                .collect(Collectors.joining());
        String reasoning = tokens.stream()
                .filter(t -> t.isStepDelta() && "reasoning".equals(t.channel()))
                .map(StreamToken::text)
                .collect(Collectors.joining());

        List<String> toolCalls = new ArrayList<>();
        for (StreamToken token : timeline) {
            if (token.isStep() && token.step() != null && token.step().id() != null
                    && token.step().id().startsWith("tool-")) {
                toolCalls.add(token.step().id());
            }
        }

        String summaryLine = AgentNodeDetailSummarizer.summarize(answer, reasoning, toolCalls.size());
        AgentNodeOutput output = new AgentNodeOutput(answer, toolCalls);
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", output.answer());
        outputs.put("output", output.answer());
        outputs.put("toolCalls", String.join(",", output.toolCalls()));
        outputs.put("detail", summaryLine);
        return NodeResult.ok(outputs, timeline);
    }
}
