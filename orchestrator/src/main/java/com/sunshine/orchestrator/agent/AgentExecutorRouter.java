package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.AgentCatalogEntry;
import com.sunshine.orchestrator.client.ExternalAgentClient;
import com.sunshine.orchestrator.client.StreamToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 智能体执行统一分派：按 {@code source} 路由。
 * INTERNAL（或未指定智能体）→ AgentRuntime.run（进程内）；EXTERNAL → A2A Client 调用远端。
 * SpawnSubagentTool 不感知 source，仅此层分派。
 */
@Slf4j
@Component
public class AgentExecutorRouter {

    private final AgentRuntime agentRuntime;
    private final ExternalAgentClient externalAgentClient;

    public AgentExecutorRouter(
            @Lazy AgentRuntime agentRuntime,
            ExternalAgentClient externalAgentClient) {
        this.agentRuntime = agentRuntime;
        this.externalAgentClient = externalAgentClient;
    }

    /**
     * @param agent           预定义智能体；为 null 时等同 INTERNAL（临时子 Agent）
     * @param internalRequest INTERNAL 路径的执行请求（EXTERNAL 时忽略）
     * @param query           任务描述（EXTERNAL 传给远端）
     * @param contextBlocks   上下文块（EXTERNAL 注入 A2A message）
     */
    public Flux<StreamToken> dispatch(
            AgentCatalogEntry agent,
            AgentRunRequest internalRequest,
            String query,
            List<String> contextBlocks) {
        if (agent != null && agent.source() == AgentCatalogEntry.AgentSource.EXTERNAL) {
            log.info("[AgentExecutorRouter] dispatch EXTERNAL agent={} endpoint override={}",
                    agent.id(), agent.endpointOverride() != null ? "yes" : "no");
            return externalAgentClient.invoke(agent, query, contextBlocks);
        }
        return agentRuntime.run(internalRequest);
    }
}
