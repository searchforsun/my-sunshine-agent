package com.sunshine.llm.adapter;

import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * LLM 厂商适配器接口
 * 每个厂商（DeepSeek / Qwen / GLM）实现此接口
 */
public interface LlmAdapter {

    /** 判断是否支持该模型 */
    boolean supports(String model);

    /** 非流式调用 */
    Mono<ChatCompletionResponse> chat(ChatCompletionRequest request);

    /** 流式调用 — 返回 SSE 事件流 */
    Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request);
}
