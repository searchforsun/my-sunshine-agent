package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamDeltaNormalizer;
import com.sunshine.orchestrator.client.StreamToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** react 模式 — 整单 ReAct Agent */
@Component
@RequiredArgsConstructor
public class ReactExecutor {

    private final AgentRuntime agentRuntime;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return agentRuntime.run(AgentRunRequest.main(
                        ctx.memory(), ctx.userContent(), ctx.userId(), ctx.tenantId(), ctx.assistantMsgId()))
                .transform(StreamDeltaNormalizer::normalizeTokens);
    }
}
