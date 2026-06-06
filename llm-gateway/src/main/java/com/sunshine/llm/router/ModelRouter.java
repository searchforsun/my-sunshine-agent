package com.sunshine.llm.router;

import com.sunshine.llm.adapter.LlmAdapter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 模型路由器 — 根据 model 参数选择厂商适配器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final List<LlmAdapter> adapters;

    @PostConstruct
    public void init() {
        log.info("[LLM-GW] 已注册 {} 个适配器", adapters.size());
        adapters.forEach(a -> log.info("[LLM-GW]   - {}", a.getClass().getSimpleName()));
    }

    public Mono<ChatCompletionResponse> route(ChatCompletionRequest request) {
        LlmAdapter adapter = findAdapter(request.getModel());
        log.info("[LLM-GW] {} → {}", request.getModel(), adapter.getClass().getSimpleName());
        return adapter.chat(request);
    }

    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        LlmAdapter adapter = findAdapter(request.getModel());
        return adapter.stream(request);
    }

    private LlmAdapter findAdapter(String model) {
        return adapters.stream()
                .filter(a -> a.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的模型: " + model));
    }
}
