package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.biz.BizSceneResolver;
import com.sunshine.orchestrator.biz.BizContextConflictArbiter;
import com.sunshine.orchestrator.biz.BusinessContextAssembler;
import com.sunshine.orchestrator.biz.SceneEmbeddingService;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
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
    private final SkillCatalogService skillCatalogService;
    private final BizSceneCatalogClient bizSceneCatalogClient;
    private final BusinessContextAssembler businessContextAssembler;
    private final BizContextConflictArbiter bizContextConflictArbiter;
    private final SceneEmbeddingService sceneEmbeddingService;
    private final ContextAssembler contextAssembler;
    private final AgentExecutionProperties executionProperties;
    private final PromptCatalogHolder catalogHolder;
    private final ObjectProvider<GenerationRegistry> generationRegistry;

    private static final String PARAM_AGENT_IDS = "agentIds";
    private static final String PARAM_SKILL_IDS = "skillIds";
    private static final String PARAM_CANDIDATE_SKILL_IDS = "candidateSkillIds";

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        Map<String, String> params = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String query = StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY))
                ? params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY).strip()
                : ctx.userContent();
        List<String> triggeredSkillIds = resolveTriggeredSkillIds(params);
        List<String> candidateSkillIds = resolveCandidateSkillIds(params);
        String skillId = triggeredSkillIds.isEmpty() ? null : triggeredSkillIds.get(0);
        return executeWithInjected(ctx, List.of(), query, skillId, triggeredSkillIds, candidateSkillIds);
    }

    /** 节点失败降级 ReAct - 注入已成功节点上下文 */
    public Flux<StreamToken> executeWithInjected(ExecutionStreamContext ctx, List<String> injectedBlocks) {
        Map<String, String> params = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String query = StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY))
                ? params.get(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY).strip()
                : ctx.userContent();
        List<String> triggeredSkillIds = resolveTriggeredSkillIds(params);
        List<String> candidateSkillIds = resolveCandidateSkillIds(params);
        String skillId = triggeredSkillIds.isEmpty() ? null : triggeredSkillIds.get(0);
        return executeWithInjected(ctx, injectedBlocks, query, skillId, triggeredSkillIds, candidateSkillIds);
    }

    /**
     * 本轮已触发 skill 集：优先 classifier 输出的多值 skillIds（逗号分隔），
     * 回退单数 skill；skillId 保留首项以兼容沙箱挂载 / 审计 / SUB 单数语义（skill-sticky S-T）。
     */
    private static List<String> resolveTriggeredSkillIds(Map<String, String> params) {
        String skillIdsRaw = params.get(PARAM_SKILL_IDS);
        if (StringUtils.hasText(skillIdsRaw)) {
            List<String> ids = java.util.Arrays.stream(skillIdsRaw.split(","))
                    .map(String::strip)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        String skillId = blankToNull(params.get(SkillBindingOutcome.PARAM_SKILL));
        return skillId != null ? List.of(skillId) : List.of();
    }

    /** 本轮候选 skill 集（S-C）：目录提权 + dynamicLoadable，可经 sunshine_search_skills 升级触发 */
    private static List<String> resolveCandidateSkillIds(Map<String, String> params) {
        String raw = params.get(PARAM_CANDIDATE_SKILL_IDS);
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private Flux<StreamToken> executeWithInjected(
            ExecutionStreamContext ctx,
            List<String> injectedBlocks,
            String query,
            String skillId,
            List<String> triggeredSkillIds,
            List<String> candidateSkillIds) {
        if (ctx.assistantMsgId() != null) {
            StepEventBridge.bindToolAudit(ctx.assistantMsgId(), new StepEventBridge.ToolAuditContext(
                    ctx.conversationId(),
                    ctx.assistantMsgId(),
                    ctx.userId(),
                    ctx.tenantId(),
                    ctx.persistedPlanId(),
                    ctx.kbId(),
                    null, null, null,
                    ctx.conversationKind()));
        }
        int checkpointThinkIteration = resolveCheckpointThinkIteration(ctx);
        List<ProcessingStep> resumeSteps = resolveResumeSteps(ctx);
        // 个人规则（soul）作为 injectedBlocks 首元素注入 MAIN Agent；子 Agent 经 sub() 工厂不继承
        List<String> blocks = new ArrayList<>();
        String wrappedRules = PersonalRulesSupport.wrap(ctx.personalRules());
        if (wrappedRules != null) {
            blocks.add(wrappedRules);
        }
        // 业务上下文权威层（authority §5.3 P3）：资源召回后解析 biz_scene；
        // 有 scene 且闸门满足（开关开 + kind=chat）才装载 Policy/任务板/偏好块
        String scene = resolveBizScene(skillId, allParams(ctx).get(PARAM_AGENT_IDS), query);
        blocks.addAll(businessContextAssembler.assemble(
                ctx.tenantId(), ctx.userId(), scene, ctx.conversationId(), ctx.conversationKind()));
        // M0（authority §2.2 方案 A）：L3 延后装配——资源召回后（resolveBizScene 之后）按
        // assemble 挂载的分区锚点召回 L3，与业务块（P3）同一注入点并行；随后 M4 冲突仲裁
        // 对 L3 材料块做权威参照过滤。
        AssembledContext memory = ctx.memory();
        if (memory != null) {
            memory = contextAssembler.attachL3(memory, buildAssembleRequest(ctx, query));
        }
        if (memory != null && StringUtils.hasText(memory.l3MaterialBlock())) {
            String filteredL3 = bizContextConflictArbiter.arbitrate(
                    ctx.tenantId(), ctx.userId(), scene, ctx.conversationId(),
                    ctx.assistantMsgId(), memory.l3MaterialBlock());
            if (filteredL3 != null) {
                memory = memory.withL3MaterialBlock(filteredL3);
            }
        }
        if (injectedBlocks != null) {
            blocks.addAll(injectedBlocks);
        }
        if (ctx.reactRestart() && !resumeSteps.isEmpty()) {
            // 不含【待决策】：DecisionResumeSupport await 成功后再并入【用户决策】（见 ReActAgentRuntime）
            blocks.addAll(ReactResumeContextSupport.buildInjectedBlocks(resumeSteps, false));
        }
        // $A $B 绑定：注入可 spawn 的智能体列表（模板 SSOT：Catalog id=react.spawn-hint）
        Map<String, String> allParams = allParams(ctx);
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
                List<String> agentIdList = java.util.Arrays.stream(ids)
                        .map(String::strip)
                        .filter(StringUtils::hasText)
                        .toList();
                String agentLines = agentCatalogService.renderForSpawnHint(agentIdList);
                if (!StringUtils.hasText(agentLines)) {
                    agentLines = null;
                }
                String firstId = agentIdList.isEmpty() ? null : agentIdList.get(0);
                if (firstId != null && agentLines != null) {
                    blocks.add(template
                            .replace("{agents}", agentLines)
                            .replace("{agentId}", firstId));
                }
            }
        }
        // 决策 re-await 挂在 ReActAgentRuntime bridge bind 之后（见 DecisionResumeSupport）
        if (ctx.reactRestart() && StringUtils.hasText(ctx.assistantMsgId()) && !resumeSteps.isEmpty()) {
            DecisionResumeSteps.bind(ctx.assistantMsgId(), resumeSteps);
        }
        // S-C：候选集消息级承载——目录「可动态加载」提权标记（sunshine_search_skills 已放开为任意 enabled 技能）
        if (StringUtils.hasText(ctx.assistantMsgId()) && !candidateSkillIds.isEmpty()) {
            com.sunshine.orchestrator.routing.SkillCandidateRegistry.bind(
                    ctx.assistantMsgId(), candidateSkillIds);
        }
        return agentRuntime.run(AgentRunRequest.main(
                        memory, query, ctx.userId(), ctx.tenantId(), ctx.assistantMsgId(),
                        blocks, skillId, ctx.reactRestart(),
                        ctx.conversationId(), checkpointThinkIteration,
                        resolveMaxItersByKind(ctx), triggeredSkillIds)
                .withConversationKind(ctx.conversationKind())
                .withCandidateSkillIds(candidateSkillIds)
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

    /** attachL3 装配请求：history 为空（分区锚点已随 memory 挂载），kind/模型从执行上下文取。 */
    private ContextAssembler.AssembleRequest buildAssembleRequest(ExecutionStreamContext ctx, String query) {
        return new ContextAssembler.AssembleRequest(
                ctx.userId(),
                ctx.tenantId(),
                ctx.conversationId(),
                List.of(),
                query,
                ctx.modelOverride(),
                ctx.conversationKind() != null ? ctx.conversationKind() : "chat",
                null,
                null);
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

    private static Map<String, String> allParams(ExecutionStreamContext ctx) {
        return ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /**
     * K2 + 权威层 P3：资源召回后解析会话 biz_scene（agent 优先 → skill 第一非空 → null）。
     * 资源召回未命中时做 embedding 回退（authority §2.1c）：query 向量化 → 与 active 场景 description
     * 余弦匹配，最高分 ≥ min-score 采纳。非空返回场景码；空则整层跳过。
     */
    private String resolveBizScene(String skillId, String agentIdsRaw, String query) {
        try {
            List<BizSceneResolver.SceneTagged> agents = new ArrayList<>();
            if (StringUtils.hasText(agentIdsRaw)) {
                for (String id : agentIdsRaw.split(",")) {
                    String aid = id.strip();
                    if (aid.isEmpty()) {
                        continue;
                    }
                    agentCatalogService.findIndex(aid)
                            .ifPresent(entry -> agents.add(new BizSceneResolver.SceneTagged(
                                    entry.id(), entry.bizScene())));
                }
            }
            List<BizSceneResolver.SceneTagged> skills = new ArrayList<>();
            if (StringUtils.hasText(skillId)) {
                skillCatalogService.find(skillId)
                        .ifPresent(entry -> skills.add(new BizSceneResolver.SceneTagged(
                                entry.id(), entry.bizScene())));
            }
            String scene = BizSceneResolver.resolve(agents, skills, bizSceneCatalogClient.activeCodes())
                    .orElse(null);
            if (scene == null && sceneEmbeddingService.enabled()) {
                java.util.Optional<SceneEmbeddingService.SceneMatch> match =
                        sceneEmbeddingService.search(query);
                if (match.isPresent()) {
                    scene = match.get().bizScene();
                    log.info("[BizScene] embedding 回退命中 scene={} score={}",
                            scene, String.format("%.2f", match.get().score()));
                }
            }
            log.info("[BizScene] resolved scene={} skill={} agents={}", scene, skillId, agentIdsRaw);
            return scene;
        } catch (Exception e) {
            log.warn("[BizScene] resolve failed: {}", e.getMessage());
            return null;
        }
    }
}
