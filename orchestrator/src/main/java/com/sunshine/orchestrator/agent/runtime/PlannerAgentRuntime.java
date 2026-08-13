package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * Planner-Executor 运行时 — ReAct + Catalog {@code planner.harness} + H1 injectedBlocks。
 * 独立角色（PLANNER），非 MAIN；不做一次性 DAG 规划。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgentRuntime implements AgentRuntime {

    public static final String HARNESS_PROMPT_ID = "planner.harness";

    private final ReActAgentRuntime reactAgentRuntime;

    @Override
    public Flux<StreamToken> run(AgentRunRequest request) {
        AgentRunRequest harness = ensureHarnessPrompt(request);
        log.info("[PlannerAgentRuntime] ReAct harness runId={} prompt={}",
                harness.runId(), harness.harnessPromptId());
        return reactAgentRuntime.runPlannerReAct(harness);
    }

    static AgentRunRequest ensureHarnessPrompt(AgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AgentRunRequest 不能为空");
        }
        if (StringUtils.hasText(request.harnessPromptId())) {
            return request;
        }
        return request.withHarnessPromptId(HARNESS_PROMPT_ID);
    }
}
