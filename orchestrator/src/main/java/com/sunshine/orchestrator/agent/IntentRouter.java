package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanParser;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 意图识别 — 输出 ExecutionPlan（simple-llm / workflow / react）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final AgentPromptProperties prompts;
    private final WorkflowCatalog workflowCatalog;
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

    /**
     * 分类并返回结构化执行计划（路由层主入口）
     */
    @SuppressWarnings("unchecked")
    public Mono<ExecutionPlan> classifyPlan(String userMessage) {
        String classifierPrompt = workflowCatalog.renderIntoClassifier(
                prompts.intentClassifierPromptOrEmpty());
        if (classifierPrompt.isEmpty()) {
            log.warn("[IntentRouter] agent.intent.classifier-prompt 未配置，默认 react");
            return Mono.just(ExecutionPlan.reactFallback("no classifier prompt"));
        }

        Map<String, Object> request = Map.of(
                "model", prompts.intentModelOrDefault(),
                "messages", List.of(
                        Map.of("role", "system", "content", classifierPrompt),
                        Map.of("role", "user", "content", userMessage)
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
                .doOnNext(plan -> log.info("[IntentRouter] 计划: mode={}, workflowId={}, reason={}",
                        plan.mode(), plan.workflowId(), plan.reason()));
    }

    /**
     * 兼容旧调用方 — 返回 legacy intent 字符串（simple/knowledge/finance/react）
     *
     * @deprecated 使用 {@link #classifyPlan(String)}
     */
    @Deprecated
    public Mono<String> classify(String userMessage) {
        return classifyPlan(userMessage).map(IntentRouter::toLegacyIntentLabel);
    }

    /** ChatController 迁移前仍识别 simple/knowledge/finance */
    static String toLegacyIntentLabel(ExecutionPlan plan) {
        return switch (plan.mode()) {
            case SIMPLE_LLM -> "simple";
            case WORKFLOW -> switch (plan.workflowId() != null ? plan.workflowId() : "") {
                case "knowledge-qa" -> "knowledge";
                case "finance-list", "finance-smart" -> "finance";
                default -> "finance";
            };
            case REACT -> "simple";
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
