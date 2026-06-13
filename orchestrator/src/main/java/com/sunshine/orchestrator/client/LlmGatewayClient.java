package com.sunshine.orchestrator.client;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.sunshine.orchestrator.conversation.ChatTurn;

import jakarta.annotation.PostConstruct;

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

public class LlmGatewayClient {



    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")

    private String baseUrl;



    @Value("${agent.model.api-key:}")

    private String apiKey;



    @Value("${agent.model.name:deepseek-v4-pro}")

    private String modelName;



    @Value("${agent.system-prompt:你是一个智能助手，优先检索知识库回答用户问题。}")

    private String systemPrompt;



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

        List<Map<String, Object>> messages = buildMessages(history, userMessage, null);

        return doStream(messages);

    }



    public Flux<StreamToken> streamContinue(List<ChatTurn> history, String userMessage, String partialAssistant) {

        List<Map<String, Object>> messages = buildMessages(history, userMessage, partialAssistant);

        return doStream(messages);

    }



    private List<Map<String, Object>> buildMessages(

            List<ChatTurn> history, String userMessage, String partialAssistant) {

        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (history != null) {

            for (ChatTurn turn : history) {

                if ("user".equals(turn.role()) || "assistant".equals(turn.role())) {

                    messages.add(Map.of("role", turn.role(), "content", turn.content()));

                }

            }

        }

        messages.add(Map.of("role", "user", "content", userMessage));

        if (partialAssistant != null && !partialAssistant.isEmpty()) {

            messages.add(Map.of("role", "assistant", "content", partialAssistant));

        }

        return messages;

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


