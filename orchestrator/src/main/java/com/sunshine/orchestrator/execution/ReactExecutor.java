package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.processing.ThinkStepIds;
import com.sunshine.orchestrator.prompt.PersonalRulesSupport;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final AgentCatalogService agentCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final PromptCatalogHolder catalogHolder;
    private final ObjectProvider<GenerationRegistry> generationRegistry;

    private static final String PARAM_AGENT_IDS = "agentIds";

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
                    ctx.kbId(),
                    null, null, null));
        }
        int checkpointThinkIteration = resolveCheckpointThinkIteration(ctx);
        List<ProcessingStep> resumeSteps = resolveResumeSteps(ctx);
        // 个人规则（soul）作为 injectedBlocks 首元素注入 MAIN Agent；子 Agent 经 sub() 工厂不继承
        List<String> blocks = new ArrayList<>();
        String wrappedRules = PersonalRulesSupport.wrap(ctx.personalRules());
        if (wrappedRules != null) {
            blocks.add(wrappedRules);
        }
        if (injectedBlocks != null) {
            blocks.addAll(injectedBlocks);
        }
        if (ctx.reactRestart() && !resumeSteps.isEmpty()) {
            blocks.addAll(ReactResumeContextSupport.buildInjectedBlocks(resumeSteps));
        }
        // $A $B 绑定：注入可 spawn 的智能体列表（模板 SSOT：Catalog id=react.spawn-hint）
        Map<String, String> allParams = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String agentIdsRaw = allParams.get(PARAM_AGENT_IDS);
        if (StringUtils.hasText(agentIdsRaw)) {
            String template = catalogHolder.snapshot().text("react.spawn-hint")
                    .map(String::strip).orElse("");
            if (!StringUtils.hasText(template)) {
                log.warn("[ReactExecutor] catalog missing id=react.spawn-hint");
                template = null;
            }
            if (template != null) {
                String[] ids = agentIdsRaw.split(",");
                StringBuilder agentLines = new StringBuilder();
                String firstId = null;
                for (String id : ids) {
                    String aid = id.strip();
                    if (aid.isEmpty()) {
                        continue;
                    }
                    if (firstId == null) {
                        firstId = aid;
                    }
                    var entry = agentCatalogService.find(aid);
                    if (entry.isPresent()) {
                        agentLines.append("- ").append(aid)
                                .append(" (").append(entry.get().displayName()).append(")");
                        if (entry.get().description() != null && !entry.get().description().isBlank()) {
                            agentLines.append(": ").append(entry.get().description());
                        }
                        agentLines.append('\n');
                    }
                }
                if (firstId != null && !agentLines.isEmpty()) {
                    blocks.add(template
                            .replace("{agents}", agentLines.toString().strip())
                            .replace("{agentId}", firstId));
                }
            }
        }
        // 决策 re-await 挂在 ReActAgentRuntime bridge bind 之后（见 DecisionResumeSupport）
        if (ctx.reactRestart() && StringUtils.hasText(ctx.assistantMsgId()) && !resumeSteps.isEmpty()) {
            DecisionResumeSteps.bind(ctx.assistantMsgId(), resumeSteps);
        }
        return agentRuntime.run(AgentRunRequest.main(
                        ctx.memory(), query, ctx.userId(), ctx.tenantId(), ctx.assistantMsgId(),
                        blocks, skillId, ctx.reactRestart(),
                        ctx.conversationId(), reactPromptId, checkpointThinkIteration,
                        resolveMaxItersByKind(ctx))
                .withModelOverride(ctx.modelOverride()));
    }

    /** task 会话（沙箱长任务）用更高的轮数上限；chat 会话传 0 取 Nacos 默认 max-iters */
    private int resolveMaxItersByKind(ExecutionStreamContext ctx) {
        AgentExecutionProperties.React react = executionProperties.getReact();
        if (react == null) {
            return 0;
        }
        if ("task".equals(ctx.conversationKind())) {
            return react.getTaskMaxIters();
        }
        return 0;
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

    /** 优先用 GenerationJob 已截断并保留 decision 的 stepsBuffer；否则回退 existingStepsJson */
    private List<ProcessingStep> resolveResumeSteps(ExecutionStreamContext ctx) {
        if (!ctx.reactRestart()) {
            return List.of();
        }
        if (StringUtils.hasText(ctx.assistantMsgId())) {
            GenerationRegistry registry = generationRegistry.getIfAvailable();
            if (registry != null) {
                var jobOpt = registry.findByMessageId(ctx.assistantMsgId().strip());
                if (jobOpt.isPresent()) {
                    List<ProcessingStep> buffered = jobOpt.get().getStepsBuffer();
                    if (buffered != null && !buffered.isEmpty()) {
                        return List.copyOf(buffered);
                    }
                }
            }
        }
        if (ctx.existingStepsJson() == null || ctx.existingStepsJson().isBlank()) {
            return List.of();
        }
        try {
            List<ProcessingStep> steps = new ArrayList<>(ProcessingStepSerde.fromJson(ctx.existingStepsJson()));
            ThinkStepIds.truncateToLastCompleteThink(steps);
            return List.copyOf(steps);
        } catch (Exception e) {
            log.warn("[ReactExecutor] resolve resume steps failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
