package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用 flash 模型生成 PlanJson */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowPlanner {

    private final AgentPromptProperties prompts;
    private final AgentExecutionProperties executionProperties;
    private final PlanCatalogRenderer catalogRenderer;
    private final PlanJsonParser planJsonParser;
    private final QueryRewriteService queryRewriteService;
    private final SkillCatalogService skillCatalogService;

    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String baseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    private WebClient webClient;

    public Mono<PlanJson> plan(ExecutionStreamContext ctx) {
        return plan(ctx, null, 1);
    }

    /** 带校验错误反馈的 Replan */
    public Mono<PlanJson> replan(ExecutionStreamContext ctx, String validationError, int attemptNo) {
        return plan(ctx, validationError, attemptNo);
    }

    /** 用户修改意见后重新规划 */
    public Mono<PlanJson> replanWithUserHint(ExecutionStreamContext ctx, String userHint, int roundNo) {
        String template = executionProperties.getPlanWorkflow().getApproval().getUserModificationTemplate();
        String hint = userHint != null ? userHint.strip() : "";
        String feedback = StringUtils.hasText(template)
                ? template.replace("{{hint}}", hint)
                : "用户对当前执行计划的修改意见：" + hint;
        return plan(ctx, feedback, roundNo);
    }

    private Mono<PlanJson> plan(ExecutionStreamContext ctx, String validationError, int attemptNo) {
        String systemPrompt = catalogRenderer.renderIntoPrompt(
                prompts.plannerOrDefault().promptOrEmpty());
        if (!StringUtils.hasText(systemPrompt)) {
            return Mono.error(new PlanParseException("agent.planner.prompt 未配置"));
        }
        String userMessage = buildUserMessage(ctx, validationError);
        AgentPromptProperties.Planner cfg = prompts.plannerOrDefault();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", cfg.getModel());
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
        request.put("max_tokens", cfg.getMaxTokens());
        request.put("temperature", cfg.getTemperature());
        request.put("skip_cache", true);
        request.put("response_format", Map.of("type", "json_object"));
        int maxInvoke = Math.max(1, executionProperties.getPlanWorkflow().getPlanner().getMaxAttempts());
        long backoffMs = executionProperties.getPlanWorkflow().getPlanner().getBackoffMs();
        return invokePlanner(request)
                .retryWhen(Retry.backoff(maxInvoke - 1, Duration.ofMillis(Math.max(backoffMs, 200)))
                        .filter(this::isInvokeRetryable)
                        .doBeforeRetry(sig -> log.warn("[WorkflowPlanner] invoke 重试 #{}: {}",
                                sig.totalRetries() + 1, sig.failure().getMessage())));
    }

    private String buildUserMessage(ExecutionStreamContext ctx, String validationError) {
        String query = ctx.userContent() != null ? ctx.userContent() : "";
        Map<String, String> routeParams = ctx.plan() != null && ctx.plan().params() != null
                ? ctx.plan().params() : Map.of();
        String plannerMode = routeParams.get(SkillBindingOutcome.PARAM_PLANNER_MODE);
        String skillId = routeParams.get(SkillBindingOutcome.PARAM_SKILL);
        if (!StringUtils.hasText(validationError)) {
            QueryRewriteOutcome outcome = queryRewriteService.rewriteForPlanner(query, ctx.assistantMsgId());
            query = outcome.effectiveQuery();
            if (SkillBindingOutcome.PLANNER_MODE_SKILL_DRIVEN.equals(plannerMode) && StringUtils.hasText(skillId)) {
                return buildSkillDrivenPlannerMessage(query, skillId.strip());
            }
            return query;
        }
        String template = executionProperties.getPlanWorkflow().getReplan().getUserFeedbackTemplate();
        if (!StringUtils.hasText(template)) {
            return query + "\n\n上次规划未通过校验：" + validationError.strip();
        }
        return query + "\n\n" + template.replace("{{error}}", validationError.strip());
    }

    /** 流程 5B：Planner 读 Skill L2 正文语义生成 Plan JSON */
    private String buildSkillDrivenPlannerMessage(String query, String skillId) {
        String overlay = skillCatalogService.overlayOrEmpty(skillId);
        String body = StringUtils.hasText(overlay) ? overlay.strip() : "(Skill 正文为空)";
        return """
                用户问题（已锁定 Skill: %s）：
                %s

                ## Skill 正文（工作流说明，请据此生成节点顺序）
                %s

                请基于 Skill 正文中的步骤说明，为上述用户问题输出一行 Plan JSON（5B Skill 驱动模式）。
                节点 type 仅 rag | tool | agent；勿含 start/answer。
                """.formatted(skillId, query, body);
    }

    private boolean isInvokeRetryable(Throwable error) {
        if (error instanceof WebClientResponseException ex) {
            int code = ex.getStatusCode().value();
            return code >= 500 || code == 429;
        }
        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("connection") || msg.contains("refused");
    }

    private Mono<PlanJson> invokePlanner(Map<String, Object> request) {
        return client().post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    logFinishReason(resp);
                    return planJsonParser.parse(extractPlannerText(resp));
                })
                .doOnNext(p -> log.info("[WorkflowPlanner] planId={}, nodes={}, reason={}",
                        p.planId(), p.nodes().size(), p.reason()));
    }

    @SuppressWarnings("unchecked")
    private static void logFinishReason(Map<String, Object> resp) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        Object reason = choices.get(0).get("finish_reason");
        Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
        log.info("[WorkflowPlanner] finish_reason={}, usage={}", reason, usage);
    }

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder().baseUrl(baseUrl).build();
        }
        return webClient;
    }

    @SuppressWarnings("unchecked")
    private static String extractPlannerText(Map<String, Object> resp) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("[WorkflowPlanner] LLM 响应无 choices");
            return "";
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            log.warn("[WorkflowPlanner] LLM 响应无 message");
            return "";
        }
        String content = stringField(message.get("content"));
        String reasoning = stringField(message.get("reasoning_content"));
        if (!StringUtils.hasText(reasoning)) {
            reasoning = stringField(message.get("reasoning"));
        }
        String best = pickBestPlannerPayload(content, reasoning);
        if (StringUtils.hasText(best)) {
            return best.trim();
        }
        log.warn("[WorkflowPlanner] message.content 与 reasoning 均为空");
        return "";
    }

    private static String pickBestPlannerPayload(String content, String reasoning) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (StringUtils.hasText(content)) {
            candidates.add(content.strip());
        }
        if (StringUtils.hasText(reasoning)) {
            candidates.add(reasoning.strip());
        }
        if (StringUtils.hasText(content) && StringUtils.hasText(reasoning)) {
            candidates.add(content.strip() + reasoning.strip());
        }
        return candidates.stream()
                .filter(s -> s.contains("\"nodes\""))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseGet(() -> StringUtils.hasText(content) ? content.strip()
                        : (StringUtils.hasText(reasoning) ? reasoning.strip() : ""));
    }

    private static String stringField(Object value) {
        return value instanceof String s ? s : null;
    }
}
