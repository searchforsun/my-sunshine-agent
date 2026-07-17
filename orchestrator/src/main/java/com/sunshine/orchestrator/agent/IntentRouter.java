package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.expert.ExpertCollaborationPlanSanitizer;
import com.sunshine.orchestrator.memory.MemoryContext;
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
 * 意图识别 — 输出 ExecutionPlan（workflow / react / plan-workflow / peer-collab + 可选 skillId）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private static final int MAX_STM_TURNS = 4;

    private final AgentPromptProperties prompts;
    private final WorkflowCatalog workflowCatalog;
    private final SkillCatalogService skillCatalogService;
    private final ExpertCollaborationPlanSanitizer expertCollaborationPlanSanitizer;
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
            log.warn("[IntentRouter] agent.intent.classifier-prompt 未配置，默认 react");
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
                .map(workflowCatalog::sanitize)
                .map(skillCatalogService::sanitizeSkillPlan)
                .map(expertCollaborationPlanSanitizer::sanitize)
                .doOnNext(plan -> log.info("[IntentRouter] 计划: mode={}, workflowId={}, skill={}, reason={}",
                        plan.mode(),
                        plan.workflowId(),
                        plan.params() != null ? plan.params().get("skill") : null,
                        plan.reason()));
    }

    private String renderClassifierPrompt() {
        String prompt = prompts.intentClassifierPromptOrEmpty();
        prompt = workflowCatalog.renderIntoClassifier(prompt);
        return skillCatalogService.renderIntoClassifier(prompt);
    }

    static String buildClassifierUserMessage(RoutingContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(ctx.clientSkillId())) {
            sb.append("【会话态】UI 已选 Skill: ").append(ctx.clientSkillId().strip()).append('\n');
        }
        MemoryContext memory = ctx.memory();
        if (memory != null) {
            if (StringUtils.hasText(memory.mtmSnippet())) {
                sb.append("【近期摘要】\n").append(memory.mtmSnippet().strip()).append("\n\n");
            }
            List<ChatTurn> turns = memory.stmTurns();
            if (turns != null && !turns.isEmpty()) {
                int from = Math.max(0, turns.size() - MAX_STM_TURNS);
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
