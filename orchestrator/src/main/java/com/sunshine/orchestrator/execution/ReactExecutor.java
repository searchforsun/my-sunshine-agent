package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamDeltaNormalizer;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** react 模式 — 整单 ReAct Agent */
@Component
@RequiredArgsConstructor
public class ReactExecutor {

    private final AgentRuntime agentRuntime;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        Map<String, String> params = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String query = StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY))
                ? params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY).strip()
                : ctx.userContent();
        String skillId = blankToNull(params.get(SkillBindingOutcome.PARAM_SKILL));
        return agentRuntime.run(AgentRunRequest.main(
                        ctx.memory(), query, ctx.userId(), ctx.tenantId(), ctx.assistantMsgId(), List.of(), skillId))
                .transform(StreamDeltaNormalizer::normalizeTokens);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
