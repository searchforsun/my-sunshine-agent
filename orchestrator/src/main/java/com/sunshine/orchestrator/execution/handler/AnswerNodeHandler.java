package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终态节点 — 将上游 LLM 答案转为流式 content token
 */
@Component
public class AnswerNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "answer";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String answer = resolveAnswer(spec, ctx);
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", answer);
        outputs.put("output", answer);
        return Mono.just(NodeResult.withContent(outputs, toContentTokens(answer)));
    }

    private static String resolveAnswer(NodeSpec spec, WorkflowContext ctx) {
        String fromParam = spec.params().get("from");
        if (fromParam != null && !fromParam.isBlank()) {
            String direct = ctx.resolvePath(fromParam);
            if (!direct.isBlank()) {
                return direct;
            }
        }
        // 扫描所有已执行节点，取最后一个非空 answer/output
        String last = "";
        for (Map.Entry<String, Map<String, String>> entry : ctx.nodeEntries()) {
            Map<String, String> node = entry.getValue();
            if (node.containsKey("answer") && !node.get("answer").isBlank()) {
                last = node.get("answer");
            } else if (node.containsKey("output") && !node.get("output").isBlank()) {
                last = node.get("output");
            }
        }
        return last;
    }

    private static List<StreamToken> toContentTokens(String answer) {
        if (answer == null || answer.isEmpty()) {
            return List.of();
        }
        List<StreamToken> tokens = new ArrayList<>();
        // 按句切分模拟流式输出
        int chunkSize = 80;
        for (int i = 0; i < answer.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, answer.length());
            tokens.add(StreamToken.content(answer.substring(i, end)));
        }
        return tokens;
    }
}
