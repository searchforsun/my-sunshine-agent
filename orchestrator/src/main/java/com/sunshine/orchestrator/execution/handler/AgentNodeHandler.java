package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.audit.SubAgentAuditService;
import com.sunshine.orchestrator.catalog.SkillBodyRenderer;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.processing.SkillLoadLabels;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.TimelineStepId;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.StreamingNodeHandler;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.common.workflow.WorkflowNodeType;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import com.sunshine.orchestrator.execution.agent.AgentStreamCollector;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** Agent 子节点 — 黑盒 f(input)→output，子 Timeline 挂 node-{id}.subSteps */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeHandler implements StreamingNodeHandler {

    private final AgentRuntime agentRuntime;
    private final SkillCatalogService skillCatalogService;
    private final SkillBodyRenderer skillBodyRenderer;
    private final SubAgentAuditService subAgentAuditService;
    private final AnswerGroundingChecker groundingChecker;

    @Override
    public String type() {
        return WorkflowNodeType.AGENT.id();
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
        AgentRunRequest request = AgentNodeRequestAssembler.build(spec, ctx, streamCtx);
        agentCollector.bindAuditContext(spec, streamCtx, request);
        bindSubAgentToolAudit(spec, streamCtx);
        java.util.function.Function<StreamToken, List<StreamToken>> fold =
                StepEventBridge.loopBodyFold(streamCtx.assistantMsgId());
        java.util.function.Function<StreamToken, List<StreamToken>> wrap = token -> {
            List<StreamToken> mid = agentCollector.ingest(token);
            if (mid == null || mid.isEmpty()) {
                return List.of();
            }
            if (fold == null) {
                return mid;
            }
            java.util.ArrayList<StreamToken> out = new java.util.ArrayList<>();
            for (StreamToken t : mid) {
                List<StreamToken> folded = fold.apply(t);
                if (folded != null) {
                    out.addAll(folded);
                }
            }
            return out;
        };
        StepEventBridge.bindTokenWrapper(request.resolveBridgeId(), wrap);
        // 子 agent（workflow 节点）绑定 skill：抽屉渲染「加载技能」步骤（摘要=技能名，detail=完整正文展开），
        // 与 spawn/MAIN 的 skill 步骤语义一致；技能正文经 composeReactInputs 已注入本次输入。
        List<StreamToken> skillHead = buildSkillStepTokens(request, agentCollector);
        Flux<StreamToken> agentStream = agentRuntime.run(request)
                .concatMap(token -> Flux.fromIterable(wrap.apply(token)))
                .doOnError(e -> AgentNodeAuditSupport.auditFailure(
                        subAgentAuditService, spec, streamCtx, request, request.skillId(), e.getMessage()));
        if (skillHead.isEmpty()) {
            return agentStream;
        }
        return Flux.concat(Flux.fromIterable(skillHead), agentStream);
    }

    /**
     * workflow agent 节点绑定技能：构造「加载技能」步骤并 fold 到 node-{id}.subSteps（抽屉），
     * 返回作为流首下发的 node 更新 token；技能未绑定/正文为空 → 空列表（不渲染该步骤）。
     */
    private List<StreamToken> buildSkillStepTokens(AgentRunRequest request, AgentStreamCollector collector) {
        if (!StringUtils.hasText(request.skillId())) {
            return List.of();
        }
        String id = request.skillId().strip();
        String after = SkillLoadLabels.after(id);
        String body = skillBodyRenderer.renderById(id, request.tenantId(), request.conversationKind());
        long ts = System.currentTimeMillis();
        ProcessingStep step = new ProcessingStep(
                TimelineStepId.SKILL.id(),
                TimelineStepId.SKILL.phase(),
                "done",
                new StepSummary(SkillLoadLabels.before(), null, after),
                null,
                ts,
                null,
                body,
                null,
                null,
                body,
                ts,
                SkillLoadLabels.before(),
                StepMetadata.fromSkillLoad(id),
                null,
                null,
                null);
        return collector.ingest(StreamToken.step(step));
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
        NodeResult result = AgentNodeResultBuilder.build(
                agentCollector, agentCollector.skillId(), groundingChecker, skillCatalogService);
        if (agentCollector.auditSpec() != null && agentCollector.auditStreamCtx() != null
                && agentCollector.auditRequest() != null) {
            AgentNodeAuditSupport.auditSuccess(
                    subAgentAuditService,
                    agentCollector.auditSpec(),
                    agentCollector.auditStreamCtx(),
                    agentCollector.auditRequest(),
                    result,
                    agentCollector.skillId());
        }
        return result;
    }

    /** 测试与 YAML 解析复用 */
    public static List<String> parseToolList(String raw) {
        return AgentNodeRequestAssembler.parseToolList(raw);
    }

    /** 测试与 YAML 解析复用 */
    public static int parseMaxIters(String raw) {
        return AgentNodeRequestAssembler.parseMaxIters(raw);
    }

    private AgentStreamCollector newCollector(NodeSpec spec, String nodeId) {
        return new AgentStreamCollector(
                nodeId,
                resolveDisplayName(spec),
                AgentNodeRequestAssembler.blankToNull(readParamString(spec, "skill")));
    }

    private String resolveDisplayName(NodeSpec spec) {
        if (StringUtils.hasText(spec.displayName())) {
            return spec.displayName().strip();
        }
        return WorkflowNodeLabels.displayName(spec.id(), spec.type());
    }

    private static void bindSubAgentToolAudit(NodeSpec spec, ExecutionStreamContext streamCtx) {
        if (!StringUtils.hasText(streamCtx.assistantMsgId())) {
            return;
        }
        String kbId = resolveAgentKbId(spec, streamCtx);
        StepEventBridge.bindToolAudit(streamCtx.assistantMsgId(), new StepEventBridge.ToolAuditContext(
                streamCtx.conversationId(),
                streamCtx.assistantMsgId(),
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.persistedPlanId(),
                kbId,
                null, null, null,
                streamCtx.conversationKind()));
    }

    private static String resolveAgentKbId(NodeSpec spec, ExecutionStreamContext streamCtx) {
        if (spec.params() != null) {
            String kbId = readParamString(spec, "kbId");
            if (StringUtils.hasText(kbId)) {
                return kbId.strip();
            }
        }
        return streamCtx.kbId();
    }

    private static String readParamString(NodeSpec spec, String key) {
        if (spec.params() == null) {
            return null;
        }
        Object v = spec.params().get(key);
        return v != null ? v.toString() : null;
    }
}
