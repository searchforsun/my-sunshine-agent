package com.sunshine.orchestrator.client;

import com.sunshine.common.model.ModelSceneKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Gateway 直连客户端 — 逐 token 流式，不经过 AgentScope。
 * 默认模型经 {@link ModelSceneResolver} 解析，不再绑定 Nacos agent.model.name。
 * 请求体携带 {@code call_site}（phase5 5.3 调用点）：内部辅助调用默认 summarize，
 * Agent 类调用（chat/plan/worker/subagent）走 AgentScope transport 注入。
 */
@Slf4j
@Component
public class LlmGatewayClient {

    public static final String CALL_SITE_CHAT = "chat";
    public static final String CALL_SITE_PLAN = "plan";
    public static final String CALL_SITE_WORKER = "worker";
    public static final String CALL_SITE_TOOL_CALL = "tool-call";
    public static final String CALL_SITE_REWRITE = "rewrite";
    public static final String CALL_SITE_SUMMARIZE = "summarize";
    public static final String CALL_SITE_SUBAGENT = "subagent";

    private final PromptComposer promptComposer;
    private final ModelSceneResolver modelSceneResolver;
    private final WebClient webClient;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    private final ObjectMapper om = new ObjectMapper();

    public LlmGatewayClient(
            PromptComposer promptComposer,
            ModelSceneResolver modelSceneResolver,
            WebClient.Builder builder) {
        this.promptComposer = promptComposer;
        this.modelSceneResolver = modelSceneResolver;
        // WebClient.Builder 可变且为 @Primary @LoadBalanced 单例：直接在共享 bean 上调
        // baseUrl/codecs/clientConnector 会污染后续所有 build（曾致 AgentScope SSE 请求
        // 继承 180s responseTimeout，静默 180s 即被本地 ReadTimeout 切断重试）。
        // 故先 build 继承 LoadBalancer filter，再 mutate 叠加本客户端配置。
        this.webClient = builder.build().mutate()
                .baseUrl("http://sunshine-llm-gateway/v1")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(180))))
                .build();
        log.info("[LlmGatewayClient] baseUrl=http://sunshine-llm-gateway/v1");
    }

    // ==================== 流式补全 ====================

    /** 流式补全 — PromptComposer 拼装后的 messages（workflow llm 等） */
    public Flux<StreamToken> streamComposed(PromptComposeRequest request) {
        return doStream(promptComposer.composeGatewayMessages(request), CALL_SITE_CHAT);
    }

    // ==================== 非流式补全 ====================

    /**
     * 非流式补全 — L1 Far / L2 抽取 / 审计等内部用途（scene=default，callSite=summarize）。
     */
    public String complete(String systemPrompt, String userContent) {
        return complete(systemPrompt, userContent, CALL_SITE_SUMMARIZE);
    }

    /** 非流式补全 — 内部用途，显式指定调用点（phase5 5.3）。 */
    public String complete(String systemPrompt, String userContent, String callSite) {
        ResolvedModelScene resolved = modelSceneResolver.resolve(ModelSceneKey.DEFAULT.key(), null);
        return complete(resolved.effectiveModel(), resolved.fallbackModel(),
                systemPrompt, userContent, callSite);
    }

    public String complete(String model, String fallbackModel, String systemPrompt, String userContent) {
        return complete(model, fallbackModel, systemPrompt, userContent, CALL_SITE_SUMMARIZE);
    }

    public String complete(String model, String fallbackModel, String systemPrompt,
                           String userContent, String callSite) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt.strip()));
        }
        messages.add(Map.of("role", "user", "content", userContent != null ? userContent : ""));
        return completeMessages(model, fallbackModel, messages, callSite).contentOrEmpty();
    }

    // ==================== 公共底层 API（供内部调用方） ====================

    /**
     * 原始请求体补全 — 供 IntentRouter / Planner 等需要自定义请求体的调用方。
     * 调用方自行构造 model / messages / temperature 等字段；可含 fallback_model。
     */
    public Mono<Map<String, Object>> completeRaw(Map<String, Object> requestBody) {
        return webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnError(e -> log.warn("[LlmGatewayClient] completeRaw 失败: {}", e.getMessage()));
    }

    // ==================== 内部实现 ====================

    private LlmCompletion completeMessages(String model, String fallbackModel,
                                           List<Map<String, Object>> messages, String callSite) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        if (StringUtils.hasText(callSite)) {
            request.put("call_site", callSite);
        }
        if (StringUtils.hasText(fallbackModel)) {
            request.put("fallback_model", fallbackModel.strip());
        }
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            if (response == null) {
                return new LlmCompletion("", "");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return new LlmCompletion("", "");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                return new LlmCompletion("", "");
            }
            String content = stringField(message.get("content"));
            String reasoning = stringField(message.get("reasoning_content"));
            if (reasoning == null || reasoning.isBlank()) {
                reasoning = stringField(message.get("reasoning"));
            }
            return new LlmCompletion(content, reasoning);
        } catch (Exception e) {
            log.warn("[LlmGatewayClient] complete 失败: {}", e.getMessage());
            return new LlmCompletion("", "");
        }
    }

    private static String stringField(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().strip();
    }

    private Flux<StreamToken> doStream(List<Map<String, Object>> messages, String callSite) {
        ResolvedModelScene resolved = modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", resolved.effectiveModel());
        request.put("messages", messages);
        request.put("stream", true);
        if (StringUtils.hasText(callSite)) {
            request.put("call_site", callSite);
        }
        if (StringUtils.hasText(resolved.fallbackModel())) {
            request.put("fallback_model", resolved.fallbackModel());
        }
        return webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .flatMap(this::parseSsePayloads)
                .flatMap(this::extractDelta)
                .transform(StreamDeltaNormalizer::normalizeTokens)
                .doOnSubscribe(s -> log.info("[LlmGatewayClient] 直连流式开始"))
                .doOnComplete(() -> log.info("[LlmGatewayClient] 直连流式完成"))
                .doOnError(e -> log.error("[LlmGatewayClient] 流式异常: {}", e.getMessage()));
    }

    private Flux<String> parseSsePayloads(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Flux.empty();
        }
        // 勿用 isBlank()："\n" 等仅含空白字符的 JSON 片段会被误丢弃
        if ("[DONE]".equals(raw.trim())) {
            return Flux.empty();
        }
        if (raw.contains("data:")) {
            return Flux.fromStream(raw.lines())
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> {
                        String payload = line.substring(5);
                        if (payload.startsWith(" ")) {
                            payload = payload.substring(1);
                        }
                        return payload;
                    })
                    .filter(s -> !s.isEmpty() && !"[DONE]".equals(s.trim()));
        }
        return Flux.just(raw);
    }

    @SuppressWarnings("unchecked")
    private Flux<StreamToken> extractDelta(String chunk) {
        String json = chunk.trim();
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return Flux.empty();
        }
        if (json.startsWith("data:")) {
            json = json.substring(5).trim();
            if (json.startsWith(" ")) {
                json = json.substring(1);
            }
        }
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return Flux.empty();
        }
        try {
            Map<String, Object> root = om.readValue(json, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Flux.empty();
            }
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) {
                return Flux.empty();
            }
            List<StreamToken> tokens = new ArrayList<>(2);
            Object reasoning = delta.get("reasoning_content");
            if (reasoning instanceof String r && !r.isEmpty()) {
                tokens.add(StreamToken.reasoning(r));
            }
            Object content = delta.get("content");
            if (content instanceof String c && !c.isEmpty()) {
                tokens.add(StreamToken.content(c));
            }
            return Flux.fromIterable(tokens);
        } catch (Exception ignored) {
        }
        return Flux.empty();
    }
}
