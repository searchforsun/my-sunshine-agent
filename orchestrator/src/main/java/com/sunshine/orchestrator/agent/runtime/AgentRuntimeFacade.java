package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 按 AgentRole 路由至 ReAct / Planner 运行时 */
@Primary
@Component
public class AgentRuntimeFacade implements AgentRuntime {

    private final ReActAgentRuntime reactAgentRuntime;
    private final PlannerAgentRuntime plannerAgentRuntime;

    public AgentRuntimeFacade(ReActAgentRuntime reactAgentRuntime, PlannerAgentRuntime plannerAgentRuntime) {
        this.reactAgentRuntime = reactAgentRuntime;
        this.plannerAgentRuntime = plannerAgentRuntime;
    }

    @Override
    public Flux<StreamToken> run(AgentRunRequest request) {
        if (request.role() == AgentRole.PLANNER) {
            return plannerAgentRuntime.run(request);
        }
        return reactAgentRuntime.run(request);
    }
}
