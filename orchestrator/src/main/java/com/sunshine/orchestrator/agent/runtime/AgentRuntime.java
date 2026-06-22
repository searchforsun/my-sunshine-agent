package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import reactor.core.publisher.Flux;

/** 主/子/Planner 统一执行契约（Task 3.10.1） */
public interface AgentRuntime {
    Flux<StreamToken> run(AgentRunRequest request);
}
