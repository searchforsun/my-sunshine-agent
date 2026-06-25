package com.sunshine.orchestrator.execution.handler;



import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;

import com.sunshine.orchestrator.agent.runtime.AgentRuntime;

import com.sunshine.orchestrator.audit.SubAgentAuditService;

import com.sunshine.orchestrator.catalog.SkillCatalogService;

import com.sunshine.orchestrator.client.StreamToken;

import com.sunshine.orchestrator.execution.ExecutionStreamContext;

import com.sunshine.orchestrator.execution.NodeResult;

import com.sunshine.orchestrator.execution.NodeSpec;

import com.sunshine.orchestrator.execution.StreamingNodeHandler;

import com.sunshine.orchestrator.execution.WorkflowContext;

import com.sunshine.orchestrator.execution.WorkflowNodeLabels;

import com.sunshine.orchestrator.execution.WorkflowStreamCollector;

import com.sunshine.orchestrator.execution.agent.AgentNodeDetailSummarizer;

import com.sunshine.orchestrator.execution.agent.AgentNodeOutput;

import com.sunshine.orchestrator.execution.agent.AgentStreamCollector;

import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;

import com.sunshine.orchestrator.grounding.GroundingEvidenceSupport;

import com.sunshine.orchestrator.grounding.GroundingVerdict;

import com.sunshine.orchestrator.memory.MemoryContext;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;

import reactor.core.publisher.Mono;



import java.util.Arrays;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.stream.Collectors;



/** Agent 子节点 — 黑盒 f(input)→output，子 Timeline 挂 node-{id}.subSteps */

@Slf4j

@Component

@RequiredArgsConstructor

public class AgentNodeHandler implements StreamingNodeHandler {



    private final AgentRuntime agentRuntime;

    private final SkillCatalogService skillCatalogService;

    private final SubAgentAuditService subAgentAuditService;

    private final AnswerGroundingChecker groundingChecker;



    @Override

    public String type() {

        return "agent";

    }



    @Override

    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {

        AgentStreamCollector collector = newCollector(spec, spec.id());

        return streamTokens(spec, ctx, streamCtx, spec.id(), collector)

                .then(Mono.fromSupplier(() -> buildResult(collector)))

                .onErrorResume(e -> {

                    log.warn("[AgentNodeHandler] 子 Agent 失败: {}", e.getMessage());

                    return Mono.just(NodeResult.fail(e.getMessage()));

                });

    }



    @Override

    public Flux<StreamToken> streamTokens(

            NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx, String nodeId) {

        return streamTokens(spec, ctx, streamCtx, nodeId, newCollector(spec, nodeId));

    }



    @Override

    public Flux<StreamToken> streamTokens(

            NodeSpec spec,

            WorkflowContext ctx,

            ExecutionStreamContext streamCtx,

            String nodeId,

            WorkflowStreamCollector collector) {

        if (!(collector instanceof AgentStreamCollector agentCollector)) {

            return Flux.error(new IllegalStateException("agent 节点需要 AgentStreamCollector"));

        }

        AgentRunRequest request = buildRequest(spec, ctx, streamCtx);

        agentCollector.bindAuditContext(spec, streamCtx, request);

        return agentRuntime.run(request)

                .concatMap(token -> Flux.fromIterable(agentCollector.ingest(token)))

                .doOnError(e -> auditSubAgentFailure(spec, streamCtx, request, request.skillId(), e.getMessage()));

    }



    @Override

    public WorkflowStreamCollector createStreamCollector(NodeSpec spec, String nodeId) {

        return newCollector(spec, nodeId);

    }



    @Override

    public NodeResult buildResult(WorkflowStreamCollector collector) {

        if (!(collector instanceof AgentStreamCollector agentCollector)) {

            return NodeResult.fail("agent 节点内部错误：collector 类型不匹配");

        }

        NodeResult result = toNodeResult(agentCollector, agentCollector.skillId());

        if (agentCollector.auditSpec() != null && agentCollector.auditStreamCtx() != null

                && agentCollector.auditRequest() != null) {

            auditSubAgentRun(

                    agentCollector.auditSpec(),

                    agentCollector.auditStreamCtx(),

                    agentCollector.auditRequest(),

                    result,

                    agentCollector.skillId());

        }

        return result;

    }



    private AgentStreamCollector newCollector(NodeSpec spec, String nodeId) {

        return new AgentStreamCollector(

                nodeId,

                resolveDisplayName(spec),

                blankToNull(spec.params() != null ? spec.params().get("skill") : null));

    }



    private AgentRunRequest buildRequest(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {

        Map<String, String> params = spec.params() != null ? spec.params() : Map.of();

        String query = params.getOrDefault("query", ctx.resolvePath("start.userQuery"));

        List<String> injected = parseInjectedBlocks(params.getOrDefault("context", ""));

        String skillId = blankToNull(params.get("skill"));

        List<String> tools = parseToolList(params.get("tools"));

        int maxIters = parseMaxIters(params.get("maxIters"));

        return AgentRunRequest.sub(

                MemoryContext.forSubAgent(),

                query,

                injected,

                streamCtx.userId(),

                streamCtx.tenantId(),

                skillId,

                tools,

                blankToNull(params.get("systemOverlay")),

                maxIters);

    }



    private void auditSubAgentRun(

            NodeSpec spec,

            ExecutionStreamContext streamCtx,

            AgentRunRequest request,

            NodeResult result,

            String skillId) {

        if (result == null || !result.success()) {

            return;

        }

        String summary = result.safeOutputs().getOrDefault("detail", result.safeOutputs().getOrDefault("answer", ""));

        subAgentAuditService.subAgentRun(

                streamCtx.conversationId(),

                streamCtx.assistantMsgId(),

                streamCtx.userId(),

                streamCtx.tenantId(),

                streamCtx.persistedPlanId(),

                spec.id(),

                request.runId(),

                skillId,

                parseToolCalls(result.safeOutputs().get("toolCalls")),

                summary,

                "ok");

    }



    private void auditSubAgentFailure(

            NodeSpec spec,

            ExecutionStreamContext streamCtx,

            AgentRunRequest request,

            String skillId,

            String error) {

        subAgentAuditService.subAgentRun(

                streamCtx.conversationId(),

                streamCtx.assistantMsgId(),

                streamCtx.userId(),

                streamCtx.tenantId(),

                streamCtx.persistedPlanId(),

                spec.id(),

                request.runId(),

                skillId,

                List.of(),

                error,

                "failed");

    }



    private static List<String> parseToolCalls(String raw) {

        if (raw == null || raw.isBlank()) {

            return List.of();

        }

        return Arrays.stream(raw.split(","))

                .map(String::strip)

                .filter(StringUtils::hasText)

                .toList();

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



    private NodeResult toNodeResult(AgentStreamCollector collector, String skillId) {

        String answer = collector.content();

        List<String> injected = collector.auditRequest() != null

                ? collector.auditRequest().injectedBlocks() : List.of();

        GroundingVerdict verdict = groundingChecker.check(

                answer,

                GroundingEvidenceSupport.fromSubAgent(

                        collector.toolCalls(), collector.subSteps(), injected));

        if (!verdict.passed()) {

            log.warn("[AgentNodeHandler] 子 Agent Grounding 未通过: {}", verdict.reason());

            return NodeResult.fail(verdict.reason());

        }

        String reasoning = collector.subSteps().stream()

                .filter(s -> s.id() != null && s.id().startsWith("think"))

                .map(s -> s.reasoning() != null ? s.reasoning() : "")

                .collect(Collectors.joining());

        List<String> toolCalls = collector.toolCalls();

        String summaryLine = AgentNodeDetailSummarizer.summarize(answer, reasoning, toolCalls.size());

        String expandDetail = AgentNodeDetailSummarizer.expandDetail(resolveSkillLabel(skillId), answer);

        AgentNodeOutput output = new AgentNodeOutput(answer, toolCalls);

        Map<String, String> outputs = new LinkedHashMap<>();

        outputs.put("answer", output.answer());

        outputs.put("output", output.answer());

        outputs.put("toolCalls", String.join(",", output.toolCalls()));

        outputs.put("detail", summaryLine);

        outputs.put("expandDetail", expandDetail);

        return NodeResult.ok(outputs);

    }



    private String resolveDisplayName(NodeSpec spec) {

        if (StringUtils.hasText(spec.displayName())) {

            return spec.displayName().strip();

        }

        return WorkflowNodeLabels.displayName(spec.id(), spec.type());

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


