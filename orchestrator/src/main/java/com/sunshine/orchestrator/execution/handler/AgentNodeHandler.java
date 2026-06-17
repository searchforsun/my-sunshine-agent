package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.SunshineAgent;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
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

/**
 * Agent 子节点 — 包装 SunshineAgent 为 f(input)→output，引擎不交给 LLM 调度
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeHandler implements NodeHandler {

    private final SunshineAgent sunshineAgent;

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String query = spec.params().getOrDefault("query", ctx.resolvePath("start.userQuery"));
        String injectedContext = spec.params().getOrDefault("context", "");

        // 子 Agent 不绑定主 assistantMsgId，避免 StepEventBridge 与主流冲突
        return sunshineAgent.chatAsSubAgent(
                        streamCtx.memory(), query, injectedContext,
                        streamCtx.userId(), streamCtx.tenantId(), null)
                .collectList()
                .map(tokens -> toNodeResult(tokens, query))
                .onErrorResume(e -> {
                    log.warn("[AgentNodeHandler] 子 Agent 失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    private static NodeResult toNodeResult(List<StreamToken> tokens, String query) {
        List<StreamToken> timeline = tokens.stream()
                .filter(t -> t.isStep() || t.isStepDelta())
                .toList();
        String answer = tokens.stream()
                .filter(StreamToken::isContent)
                .map(StreamToken::text)
                .collect(Collectors.joining());

        List<String> toolCalls = new ArrayList<>();
        for (StreamToken token : timeline) {
            if (token.isStep() && token.step() != null && token.step().id() != null
                    && token.step().id().startsWith("tool-")) {
                toolCalls.add(token.step().id());
            }
        }

        AgentNodeOutput output = new AgentNodeOutput(answer, toolCalls);
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", output.answer());
        outputs.put("output", output.answer());
        outputs.put("toolCalls", String.join(",", output.toolCalls()));
        return NodeResult.ok(outputs, timeline);
    }
}
