package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.SunshineAgent;
import com.sunshine.orchestrator.client.StreamToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * react 模式 — 整单 ReAct Agent
 */
@Component
@RequiredArgsConstructor
public class ReactExecutor {

    private final SunshineAgent sunshineAgent;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return sunshineAgent.chat(
                ctx.memory(), ctx.userContent(), ctx.userId(), ctx.tenantId(),
                ctx.assistantMsgId());
    }
}
