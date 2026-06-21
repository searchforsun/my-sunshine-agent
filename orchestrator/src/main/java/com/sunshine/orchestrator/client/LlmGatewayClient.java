package com.sunshine.orchestrator.client;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.core.ParameterizedTypeReference;

import org.springframework.http.MediaType;

import org.springframework.http.codec.ServerSentEvent;

import org.springframework.stereotype.Component;

import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;



import java.util.ArrayList;

import java.util.List;

import java.util.Map;



/**

 * LLM Gateway 直连客户端 — 逐 token 流式，不经过 AgentScope

 */

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGatewayClient {

    private final PromptComposer promptComposer;

    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String baseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    private WebClient webClient;

    private final ObjectMapper om = new ObjectMapper();



    @PostConstruct

    public void init() {

        this.webClient = WebClient.builder()

                .baseUrl(baseUrl)

                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))

                .build();

        log.info("[LlmGatewayClient] baseUrl={}", baseUrl);

    }



    public Flux<StreamToken> streamDirectly(String userMessage) {

        return streamWithHistory(List.of(), userMessage);

    }



    public Flux<StreamToken> streamWithHistory(List<ChatTurn> history, String userMessage) {
        return streamWithMemory(MemoryContext.empty(), userMessage);
    }

    public Flux<StreamToken> streamWithMemory(MemoryContext memory, String userMessage) {
        List<Map<String, Object>> messages = buildMessages(memory, userMessage, null);
        return doStream(messages);
    }

    public Flux<StreamToken> streamContinue(List<ChatTurn> history, String userMessage, String partialAssistant) {
        return streamContinue(MemoryContext.empty(), userMessage, partialAssistant);
    }

    public Flux<StreamToken> streamContinue(MemoryContext memory, String userMessage, String partialAssistant) {
        List<Map<String, Object>> messages = buildMessages(memory, userMessage, partialAssistant);
        return doStream(messages);
    }

    /**
     * 非流式补全 — MTM 会话摘要等内部用途。
     */
    public String complete(String systemPrompt, String userContent) {
        return complete(modelName, systemPrompt, userContent);
    }

    /**
     * 非流式补全 — 指定模型（QueryRewrite 等内部用途）。
     */
    public String complete(String model, String systemPrompt, String userContent) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt.strip()));
        }
        messages.add(Map.of("role", "user", "content", userContent != null ? userContent : ""));
        return completeMessages(model, messages);
    }

    /** 非流式补全 — PromptComposer 拼装后的 messages（workflow llm 等） */
    public String completeComposed(PromptComposeRequest request) {
        return completeMessages(modelName, promptComposer.composeGatewayMessages(request));
    }

    private String completeMessages(String model, List<Map<String, Object>> messages) {
        Map<String, Object> request = Map.of(
                "model", model,
                "messages", messages,
                "stream", false);
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
                return "";
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return "";
            }
            return message.get("content").toString().strip();
        } catch (Exception e) {
            log.warn("[LlmGatewayClient] complete 失败: {}", e.getMessage());
            return "";
        }
    }



    private List<Map<String, Object>> buildMessages(
            MemoryContext memory, String userMessage, String partialAssistant) {
        PromptComposeRequest request = partialAssistant != null && !partialAssistant.isEmpty()
                ? PromptComposeRequest.forSimpleLlmContinue(memory, userMessage, partialAssistant)
                : PromptComposeRequest.forSimpleLlm(memory, userMessage);
        return promptComposer.composeGatewayMessages(request);
    }



    private Flux<StreamToken> doStream(List<Map<String, Object>> messages) {

        Map<String, Object> request = Map.of(

                "model", modelName,

                "messages", messages,

                "stream", true

        );



        return webClient.post()

                .uri("/chat/completions/stream")

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


