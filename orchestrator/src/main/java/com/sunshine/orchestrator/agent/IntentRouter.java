package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanParser;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 意图识别 — 输出 ExecutionPlan（workflow / react / plan-workflow + 可选 skillId）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    /** 意图分类注入的近期轮次上限（L1 mid/near 尾部）。 */
    private static final int MAX_INTENT_CONTEXT_TURNS = 4;

    private final AgentPromptProperties prompts;
    private final PromptCatalogHolder catalogHolder;
    private final WorkflowCatalog workflowCatalog;
    private final SkillCatalogService skillCatalogService;
    private final ExecutionPlanParser planParser;

    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String baseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    private WebClient webClient;

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder().baseUrl(baseUrl).build();
        }
        return webClient;
    }

    /** 兼容仅传用户句的调用方 */
    public Mono<ExecutionPlan> classifyPlan(String userMessage) {
        return classifyPlan(new RoutingContext(userMessage, null));
    }

    /**
     * 分类并返回结构化执行计划（L3 主入口 — 含 Skill Catalog + 会话上下文）
     */
    @SuppressWarnings("unchecked")
    public Mono<ExecutionPlan> classifyPlan(RoutingContext ctx) {
        String classifierPrompt = renderClassifierPrompt();
        if (classifierPrompt.isEmpty()) {
            log.warn("[IntentRouter] catalog intent.classifier 未配置，默认 react");
            return Mono.just(ExecutionPlan.reactFallback("no classifier prompt"));
        }
        String userContent = buildClassifierUserMessage(ctx);

        Map<String, Object> request = Map.of(
                "model", prompts.intentModelOrDefault(),
                "messages", List.of(
                        Map.of("role", "system", "content", classifierPrompt),
                        Map.of("role", "user", "content", userContent)
                ),
                "max_tokens", 256,
                "temperature", 0
        );

        return client().post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> extractContent(resp))
                .defaultIfEmpty("")
                .map(planParser::parse)
                .map(plan -> ctx.lockedMode() != null
                        ? applyLockedMode(plan, ctx.lockedMode())
                        : workflowCatalog.sanitize(plan))
                .map(skillCatalogService::sanitizeSkillPlan)
                .doOnNext(plan -> log.info("[IntentRouter] 计划: mode={}, workflowId={}, skill={}, reason={}, locked={}",
                        plan.mode(),
                        plan.workflowId(),
                        plan.params() != null ? plan.params().get("skill") : null,
                        plan.reason(),
                        ctx.lockedMode()));
    }

    /** 强制模式：解析后锁死 mode，保留 LLM 给出的绑定字段 */
    static ExecutionPlan applyLockedMode(ExecutionPlan plan, ExecutionMode locked) {
        if (locked == null || plan == null || plan.mode() == locked) {
            return plan;
        }
        return new ExecutionPlan(locked, plan.workflowId(), plan.params(), plan.reason(), plan.ruleId());
    }

    /**
     * lockedMode 时跳过 {@link WorkflowCatalog#sanitize}：sanitize 会把未知/缺定义 workflow
     * 降级为 react，随后再锁回 WORKFLOW 会丢掉 workflowId；强制路径由 ForcedExecutionRouter 校验。
     */
    private String renderClassifierPrompt() {
        String prompt = catalogHolder.snapshot().text("intent.classifier").map(String::strip).orElse("");
        if (!StringUtils.hasText(prompt)) {
            return "";
        }
        prompt = workflowCatalog.renderIntoClassifier(prompt);
        return skillCatalogService.renderIntoClassifier(prompt);
    }

    static String buildClassifierUserMessage(RoutingContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.lockedMode() != null) {
            sb.append("【模式锁定】执行模式已固定为 ")
                    .append(lockedModeLabel(ctx.lockedMode()))
                    .append("，输出 JSON 的 mode 必须为此值；勿改 mode，仅填写该模式下的 workflowId / skillId / reactPromptId / params。\n");
        }
        if (StringUtils.hasText(ctx.clientSkillId())) {
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

    private static String lockedModeLabel(ExecutionMode mode) {
        return switch (mode) {
            case REACT -> "react";
            case PLAN_WORKFLOW -> "plan-workflow";
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
