package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 起始节点 — 写入用户问题与会话上下文。
 * 若 intent 改写已生效，{@code userQuery} 使用补全后问句供 RAG/LLM 对齐检索结果。
 */
@Component
public class StartNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "start";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String original = streamCtx.userContent() != null ? streamCtx.userContent() : "";
        String effective = resolveEffectiveUserQuery(original, streamCtx.assistantMsgId());
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("userQuery", effective);
        outputs.put("originalUserQuery", original);
        outputs.put("userId", streamCtx.userId() != null ? streamCtx.userId() : "");
        outputs.put("tenantId", streamCtx.tenantId() != null ? streamCtx.tenantId() : "");
        return Mono.just(NodeResult.ok(outputs));
    }

    static String resolveEffectiveUserQuery(String original, String assistantMsgId) {
        if (assistantMsgId == null || assistantMsgId.isBlank()) {
            return original;
        }
        return QueryRewriteTrace.intentOutcome(assistantMsgId.strip())
                .filter(QueryRewriteOutcome::applied)
                .map(QueryRewriteOutcome::effectiveQuery)
                .orElse(original);
    }
}
