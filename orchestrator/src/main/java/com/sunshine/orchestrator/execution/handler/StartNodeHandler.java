package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 起始节点 — 写入用户问题与会话上下文
 */
@Component
public class StartNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "start";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("userQuery", streamCtx.userContent() != null ? streamCtx.userContent() : "");
        outputs.put("userId", streamCtx.userId() != null ? streamCtx.userId() : "");
        outputs.put("tenantId", streamCtx.tenantId() != null ? streamCtx.tenantId() : "");
        return Mono.just(NodeResult.ok(outputs));
    }
}
