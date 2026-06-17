package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.StepLabels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用节点 — 委托 tool-manager
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolNodeHandler implements NodeHandler {

    private final ToolManagerClient toolManagerClient;

    @Override
    public String type() {
        return "tool";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String tool = spec.params().getOrDefault("tool", "");
        String status = spec.params().getOrDefault("status", "pending");
        Map<String, String> invokeParams = new LinkedHashMap<>();
        if ("list_finance_messages".equals(tool)) {
            invokeParams.put("status", status);
        } else {
            spec.params().forEach((k, v) -> {
                if (!"tool".equals(k)) {
                    invokeParams.put(k, v);
                }
            });
        }

        return Mono.fromCallable(() -> toolManagerClient.invoke(tool, invokeParams))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    Map<String, String> outputs = new LinkedHashMap<>();
                    outputs.put("output", result != null ? result : "");
                    outputs.put("tool", tool);
                    List<StreamToken> toolSteps = emitToolSteps(tool, streamCtx.userContent());
                    return NodeResult.ok(outputs, toolSteps);
                })
                .onErrorResume(e -> {
                    log.warn("[ToolNodeHandler] 工具 {} 失败: {}", tool, e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    private static List<StreamToken> emitToolSteps(String tool, String userQuery) {
        String stepId = "tool-" + tool;
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(userQuery);
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending(stepId, "tool");
            session.start(stepId, StepLabels.labelFor(stepId));
            session.complete(stepId, StepLabels.toolDisplayName(stepId) + "完成");
        });
    }
}
