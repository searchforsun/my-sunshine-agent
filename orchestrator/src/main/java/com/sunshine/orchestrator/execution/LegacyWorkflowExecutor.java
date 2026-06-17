package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.SunshineAgent;
import com.sunshine.orchestrator.client.FinanceContextFormatter;
import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.client.RagContextFormatter;
import com.sunshine.orchestrator.client.RagDetailFormatter;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 过渡 — 承载原 knowledge/finance AgentFlux，Task 10 替换为 WorkflowExecutor DAG
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyWorkflowExecutor {

    private final RagClient ragClient;
    private final ToolManagerClient toolManagerClient;
    private final SunshineAgent sunshineAgent;
    private final ReactExecutor reactExecutor;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionPlan plan = ctx.plan();
        if (plan == null || plan.mode() != ExecutionMode.WORKFLOW) {
            return reactExecutor.execute(ctx);
        }
        return switch (plan.workflowId() != null ? plan.workflowId() : "") {
            case "knowledge-qa" -> {
                log.info("[Orchestrator] workflow knowledge-qa（legacy 路径）");
                yield knowledgeFlux(ctx);
            }
            case "finance-list", "finance-smart" -> {
                log.info("[Orchestrator] workflow {}（legacy 路径）", plan.workflowId());
                yield financeFlux(ctx, plan);
            }
            default -> reactExecutor.execute(ctx);
        };
    }

    private Flux<StreamToken> financeFlux(ExecutionStreamContext ctx, ExecutionPlan plan) {
        String status = plan.params() != null && plan.params().containsKey("status")
                ? plan.params().get("status")
                : "pending";
        return Mono.fromCallable(() -> toolManagerClient.invoke(
                        "list_finance_messages", Map.of("status", status)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(toolData -> {
                    String financeContext = FinanceContextFormatter.formatAgentContext(toolData, status);
                    return Flux.concat(
                            Flux.fromIterable(emitFinanceToolSteps(ctx.userContent())),
                            sunshineAgent.chat(
                                    ctx.memory(), ctx.userContent(), ctx.userId(), ctx.tenantId(),
                                    ctx.assistantMsgId(), null, financeContext));
                });
    }

    private static List<StreamToken> emitFinanceToolSteps(String userQuery) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(userQuery);
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("tool-list_finance_messages", "tool");
            session.start("tool-list_finance_messages", "tool");
            session.complete("tool-list_finance_messages", "已查询待审批财务消息列表");
        });
    }

    private Flux<StreamToken> knowledgeFlux(ExecutionStreamContext ctx) {
        long ragStartMs = System.currentTimeMillis();
        return ragClient.search(ctx.userContent(), 3)
                .flatMapMany(hits -> {
                    long ragEndMs = System.currentTimeMillis();
                    List<RagClient.RagHit> results = hits != null ? hits : List.of();
                    String ragContext = RagContextFormatter.formatAgentContext(results);
                    return Flux.concat(
                            emitRagSteps(ctx.userContent(), ctx.assistantMsgId(), results, ragStartMs, ragEndMs),
                            sunshineAgent.chat(
                                    ctx.memory(), ctx.userContent(), ctx.userId(), ctx.tenantId(),
                                    ctx.assistantMsgId(), ragContext)
                    );
                })
                .onErrorResume(e -> {
                    log.warn("[Orchestrator] 知识库预检索失败: {}", e.getMessage());
                    long ragEndMs = System.currentTimeMillis();
                    return Flux.concat(
                            emitRagSteps(ctx.userContent(), ctx.assistantMsgId(), List.of(), ragStartMs, ragEndMs),
                            sunshineAgent.chat(
                                    ctx.memory(), ctx.userContent(), ctx.userId(), ctx.tenantId(),
                                    ctx.assistantMsgId())
                    );
                });
    }

    private Flux<StreamToken> emitRagSteps(
            String query, String assistantMsgId, List<RagClient.RagHit> hits,
            long ragStartMs, long ragEndMs) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(query);
        StepEventBridge.setUserQuery(assistantMsgId, query);
        String detail = RagDetailFormatter.formatDetail(hits);
        List<StreamToken> startTokens = ProcessingTimelineSupport.run(session, () -> {
            session.pending("rag", "rag");
            session.startAt("rag", "rag", ragStartMs);
            session.completeAt("rag", detail, ragEndMs);
        });
        StepEventBridge.setRagDetail(assistantMsgId, detail);
        return Flux.fromIterable(startTokens);
    }
}
