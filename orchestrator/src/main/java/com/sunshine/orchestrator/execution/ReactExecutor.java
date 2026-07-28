package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.processing.ThinkStepIds;
import com.sunshine.orchestrator.prompt.PersonalRulesSupport;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** react 模式 - 整单 ReAct Agent */
@Slf4j
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
        String reactPromptId = blankToNull(params.get("reactPromptId"));
        return executeWithInjected(ctx, List.of(), query, skillId, reactPromptId);
    }

    /** plan-workflow 降级 ReAct - 注入已成功节点上下文 */
    public Flux<StreamToken> executeWithInjected(ExecutionStreamContext ctx, List<String> injectedBlocks) {
        Map<String, String> params = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String query = StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY))
                ? params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY).strip()
                : ctx.userContent();
        String skillId = blankToNull(params.get(SkillBindingOutcome.PARAM_SKILL));
        String reactPromptId = blankToNull(params.get("reactPromptId"));
        return executeWithInjected(ctx, injectedBlocks, query, skillId, reactPromptId);
    }

    private Flux<StreamToken> executeWithInjected(
            ExecutionStreamContext ctx,
            List<String> injectedBlocks,
            String query,
            String skillId,
            String reactPromptId) {
        if (ctx.assistantMsgId() != null) {
            StepEventBridge.bindToolAudit(ctx.assistantMsgId(), new StepEventBridge.ToolAuditContext(
                    ctx.conversationId(),
                    ctx.assistantMsgId(),
                    ctx.userId(),
                    ctx.tenantId(),
                    ctx.persistedPlanId(),
                    ctx.kbId()));
        }
        int checkpointThinkIteration = resolveCheckpointThinkIteration(ctx);
        // 个人规则（soul）作为 injectedBlocks 首元素注入 MAIN Agent；子 Agent 经 sub() 工厂不继承
        List<String> blocks = new ArrayList<>();
        String wrappedRules = PersonalRulesSupport.wrap(ctx.personalRules());
        if (wrappedRules != null) {
            blocks.add(wrappedRules);
        }
        if (injectedBlocks != null) {
            blocks.addAll(injectedBlocks);
        }
        return agentRuntime.run(AgentRunRequest.main(
                        ctx.memory(), query, ctx.userId(), ctx.tenantId(), ctx.assistantMsgId(),
                        blocks, skillId, ctx.reactRestart(),
                        ctx.conversationId(), reactPromptId, checkpointThinkIteration));
    }

    /**
     * 续跑 think 轮次基线 = 「最后一个完整 think 轮」轮次。
     * AgentScope checkpoint 只存 message 历史，think-N 流式中途的半截 reasoning 不在历史里，
     * 无法从 think-N 流式断点精确续传。故仅当「think-N done 且其后已有 tool 等步」（即中断发生在
     * tool 执行阶段，think-N 已完整并入 message 历史）才以 N 为基线，重放开 think-(N+1)；
     * 若 think-N 是最后一步或其后仅 tasks（中断发生在 think-N 流式中途），则回退到 think-(N-1)，
     * 丢弃半截 think-N 让其重生成，实现无感续传。
     */
    private static int resolveCheckpointThinkIteration(ExecutionStreamContext ctx) {
        if (!ctx.reactRestart() || ctx.existingStepsJson() == null || ctx.existingStepsJson().isBlank()) {
            return 0;
        }
        try {
            List<ProcessingStep> steps = ProcessingStepSerde.fromJson(ctx.existingStepsJson());
            int baseline = ThinkStepIds.lastCompleteThinkIteration(steps);
            log.info("[ReactExecutor] checkpoint think iteration msg={} baseline={}",
                    ctx.assistantMsgId(), baseline);
            return baseline;
        } catch (Exception e) {
            log.warn("[ReactExecutor] resolve checkpoint think iteration failed: {}", e.getMessage());
            return 0;
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
