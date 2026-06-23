package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.agent.AgentNodeDetailSummarizer;
import com.sunshine.orchestrator.execution.agent.AgentNodeOutput;
import com.sunshine.orchestrator.memory.MemoryContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final SkillCatalogService skillCatalogService;

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, String> params = spec.params() != null ? spec.params() : Map.of();
        String query = params.getOrDefault("query", ctx.resolvePath("start.userQuery"));
        if (!StringUtils.hasText(query)) {
            return Mono.just(NodeResult.fail("agent 节点缺少 query"));
        }
        List<String> injected = parseInjectedBlocks(params.getOrDefault("context", ""));
        String skillId = blankToNull(params.get("skill"));
        List<String> tools = parseToolList(params.get("tools"));
        int maxIters = parseMaxIters(params.get("maxIters"));

        AgentRunRequest request = AgentRunRequest.sub(
                MemoryContext.forSubAgent(),
                query,
                injected,
                streamCtx.userId(),
                streamCtx.tenantId(),
                skillId,
                tools,
                blankToNull(params.get("systemOverlay")),
                maxIters);

        return agentRuntime.run(request)
                .collectList()
                .map(tokens -> toNodeResult(tokens, skillId))
                .onErrorResume(e -> {
                    log.warn("[AgentNodeHandler] 子 Agent 失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    static List<String> parseInjectedBlocks(String context) {
        if (context == null || context.isBlank()) {
            return List.of();
        }
        return List.of(context.strip());
    }

    static List<String> parseToolList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        List<String> tools = Arrays.stream(normalized.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
        return tools.isEmpty() ? null : tools;
    }

    static int parseMaxIters(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value > 0 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private NodeResult toNodeResult(List<StreamToken> tokens, String skillId) {
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
        String expandDetail = AgentNodeDetailSummarizer.expandDetail(resolveSkillLabel(skillId), answer);
        AgentNodeOutput output = new AgentNodeOutput(answer, toolCalls);
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", output.answer());
        outputs.put("output", output.answer());
        outputs.put("toolCalls", String.join(",", output.toolCalls()));
        outputs.put("detail", summaryLine);
        outputs.put("expandDetail", expandDetail);
        return NodeResult.ok(outputs, timeline);
    }

    private String resolveSkillLabel(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return null;
        }
        return skillCatalogService.find(skillId.strip())
                .map(entry -> StringUtils.hasText(entry.displayName()) ? entry.displayName().strip() : skillId)
                .orElse(skillId.strip());
    }
}
