package com.sunshine.orchestrator.agent;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanParser;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别 — 分轨收集绑定（轨 A：skill/agent；轨 B：workflow）；禁止改写 executionMode。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    /** 意图分类注入的近期轮次上限（L1 mid/near 尾部）。 */
    private static final int MAX_INTENT_CONTEXT_TURNS = 4;
    private static final String PARAM_AGENT_IDS = "agentIds";

    private final PromptCatalogHolder catalogHolder;
    private final WorkflowCatalog workflowCatalog;
    private final SkillCatalogService skillCatalogService;
    private final ExecutionPlanParser planParser;
    private final LlmGatewayClient llmGateway;
    private final ModelSceneResolver modelSceneResolver;

    /** 兼容仅传用户句的调用方 */
    public Mono<ExecutionPlan> classifyPlan(String userMessage) {
        return classifyPlan(new RoutingContext(userMessage, null));
    }

    /**
     * 分类并返回结构化执行计划（L3 主入口 — 含 Skill Catalog + 会话上下文）
     */
    @SuppressWarnings("unchecked")
    public Mono<ExecutionPlan> classifyPlan(RoutingContext ctx) {
        String classifierPrompt = renderClassifierPrompt(ctx);
        if (classifierPrompt.isEmpty()) {
            log.warn("[IntentRouter] catalog intent.classifier 未配置，默认 react");
            return Mono.just(applyLockedMode(
                    ExecutionPlan.reactFallback("no classifier prompt"), ctx.effectiveLockedMode()));
        }
        String userContent = buildClassifierUserMessage(ctx);

        ResolvedModelScene model = modelSceneResolver.resolve(ModelSceneKey.INTENT.key(), null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model.effectiveModel());
        request.put("messages", List.of(
                Map.of("role", "system", "content", classifierPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        request.put("max_tokens", 256);
        request.put("temperature", 0);
        if (StringUtils.hasText(model.fallbackModel())) {
            request.put("fallback_model", model.fallbackModel());
        }

        return llmGateway.completeRaw(request)
                .map(IntentRouter::extractContent)
                .defaultIfEmpty("")
                .map(planParser::parse)
                .map(plan -> ctx.lockedMode() != null || ctx.preference() != null
                        ? applyLockedMode(plan, ctx.effectiveLockedMode())
                        : workflowCatalog.sanitize(plan))
                .map(skillCatalogService::sanitizeSkillPlan)
                .doOnNext(plan -> log.info(
                        "[IntentRouter] 计划: mode={}, workflowId={}, skill={}, reason={}, locked={}, kind={}",
                        plan.mode(),
                        plan.workflowId(),
                        plan.params() != null ? plan.params().get(SkillBindingOutcome.PARAM_SKILL) : null,
                        plan.reason(),
                        ctx.effectiveLockedMode(),
                        ctx.kindOrDefault()));
    }

    /**
     * 强制模式：锁死 mode，并按轨裁剪绑定（轨 A 去 workflowId；轨 B 去 skill/agent）。
     * 忽略 LLM 输出的 planMode / executionMode（解析阶段亦不采纳）。
     */
    public static ExecutionPlan applyLockedMode(ExecutionPlan plan, ExecutionMode locked) {
        if (plan == null) {
            return null;
        }
        if (locked == null) {
            return plan;
        }
        String workflowId = locked == ExecutionMode.WORKFLOW ? plan.workflowId() : null;
        Map<String, String> params = filterParamsForTrack(plan.params(), locked);
        return new ExecutionPlan(locked, workflowId, params, plan.reason(), plan.ruleId(), plan.routingTraces());
    }

    private static Map<String, String> filterParamsForTrack(Map<String, String> params, ExecutionMode locked) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!StringUtils.hasText(e.getKey()) || !StringUtils.hasText(e.getValue())) {
                continue;
            }
            if (locked == ExecutionMode.WORKFLOW) {
                if (SkillBindingOutcome.PARAM_SKILL.equals(e.getKey())
                        || PARAM_AGENT_IDS.equals(e.getKey())
                        || SkillBindingOutcome.PARAM_PLANNER_MODE.equals(e.getKey())) {
                    continue;
                }
            }
            out.put(e.getKey(), e.getValue());
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    /**
     * lockedMode 时跳过 {@link WorkflowCatalog#sanitize}：sanitize 会把未知/缺定义 workflow
     * 降级为 react，随后再锁回 WORKFLOW 会丢掉 workflowId；强制路径由 ForcedExecutionRouter 校验。
     */
    private String renderClassifierPrompt(RoutingContext ctx) {
        String prompt = catalogHolder.snapshot().text("intent.classifier").map(String::strip).orElse("");
        if (!StringUtils.hasText(prompt)) {
            return "";
        }
        // 目录按会话 kind 过滤（保留 all + 同 kind）；输出字段由【模式锁定·轨A/B】+ applyLockedMode 约束
        prompt = workflowCatalog.renderIntoClassifier(prompt);
        return skillCatalogService.renderIntoClassifier(prompt, ctx.kindOrDefault());
    }

    static String buildClassifierUserMessage(RoutingContext ctx) {
        StringBuilder sb = new StringBuilder();
        ExecutionMode locked = ctx.lockedMode() != null ? ctx.lockedMode() : null;
        if (locked != null) {
            sb.append(trackLockInstruction(locked));
        }
        if (StringUtils.hasText(ctx.clientSkillId()) && ctx.isAgentSkillTrack()) {
            sb.append("【会话态】UI 已选 Skill: ").append(ctx.clientSkillId().strip()).append('\n');
        }
        AssembledContext memory = ctx.memory();
        if (memory != null) {
            String summary = StringUtils.hasText(memory.farSummaryBlock())
                    ? memory.farSummaryBlock()
                    : memory.l2SystemBlock();
            if (StringUtils.hasText(summary)) {
                sb.append("【近期摘要】\n").append(summary.strip()).append("\n\n");
            }
            List<ChatTurn> turns = new java.util.ArrayList<>();
            if (memory.midTurns() != null) {
                turns.addAll(memory.midTurns());
            }
            if (memory.nearTurns() != null) {
                turns.addAll(memory.nearTurns());
            }
            if (!turns.isEmpty()) {
                int from = Math.max(0, turns.size() - MAX_INTENT_CONTEXT_TURNS);
                sb.append("【近期对话】\n");
                for (int i = from; i < turns.size(); i++) {
                    ChatTurn turn = turns.get(i);
                    if (turn != null && StringUtils.hasText(turn.content())) {
                        sb.append(turn.role()).append(": ").append(turn.content().strip()).append('\n');
                    }
                }
                sb.append('\n');
            }
        }
        sb.append("【当前问题】\n").append(ctx.userMessage());
        return sb.toString();
    }

    private static String trackLockInstruction(ExecutionMode locked) {
        return switch (locked) {
            case FAST, PRO -> "【模式锁定·轨A】执行模式已固定为 " + lockedModeLabel(locked)
                    + "。只回复一行 JSON，字段仅允许 agentIds/skillIds/confidence/reason"
                    + "（可用 skillId 单数）；禁止输出 executionMode、planMode、mode、workflowId。\n";
            case WORKFLOW -> "【模式锁定·轨B】执行模式已固定为 workflow。"
                    + "只回复一行 JSON，字段仅允许 workflowId/confidence/reason；"
                    + "禁止输出 executionMode、planMode、mode、agentIds、skillIds、skillId。\n";
        };
    }

    private static String lockedModeLabel(ExecutionMode mode) {
        return switch (mode) {
            case FAST -> "fast";
            case PRO -> "pro";
            case WORKFLOW -> "workflow";
        };
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> resp) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");
            if (content != null) {
                return content.trim();
            }
        }
        return "";
    }
}
