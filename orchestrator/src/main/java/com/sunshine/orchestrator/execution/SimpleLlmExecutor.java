package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * simple-llm 模式 — 直连 LLM Gateway，无工具
 */
@Component
@RequiredArgsConstructor
public class SimpleLlmExecutor {

    private final LlmGatewayClient llmGateway;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        if (StringUtils.hasText(ctx.existingContent())) {
            return llmGateway.streamContinue(ctx.memory(), ctx.userContent(), ctx.existingContent());
        }
        return llmGateway.streamWithMemory(ctx.memory(), ctx.userContent());
    }
}
