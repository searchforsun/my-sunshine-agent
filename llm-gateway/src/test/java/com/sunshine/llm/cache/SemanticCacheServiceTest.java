package com.sunshine.llm.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        service = new SemanticCacheService(redisTemplate, new ObjectMapper());
    }

    private ChatCompletionRequest request(String model, String callSite) {
        ChatCompletionRequest r = new ChatCompletionRequest();
        r.setModel(model);
        r.setCallSite(callSite);
        ChatCompletionRequest.Message m = new ChatCompletionRequest.Message();
        m.setRole("user");
        m.setContent("你好");
        r.setMessages(List.of(m));
        return r;
    }

    private ChatCompletionResponse response() {
        return ChatCompletionResponse.builder()
                .model("deepseek-v4-pro")
                .choices(List.of(new ChatCompletionResponse.Choice(
                        0, new ChatCompletionResponse.Message("assistant", "你好，我是助手", null), "stop")))
                .build();
    }

    @Test
    void get_modelAuto_skipsCache() {
        assertThat(service.get(request("auto", "rewrite")).blockOptional()).isEmpty();
        verify(valueOps, never()).get(any());
    }

    @Test
    void get_modelBlank_skipsCache() {
        assertThat(service.get(request(null, "chat")).blockOptional()).isEmpty();
        verify(valueOps, never()).get(any());
    }

    @Test
    void put_modelAuto_skipsCache() {
        assertThat(service.put(request("auto", "rewrite"), response()).block()).isFalse();
        verify(valueOps, never()).set(any(), any(), any());
    }

    @Test
    void get_explicitModel_callSiteIsolated() {
        String chatKey = "llm:cache:" + cacheKeyOf("deepseek-v4-pro", "chat");
        String rewriteKey = "llm:cache:" + cacheKeyOf("deepseek-v4-pro", "rewrite");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(eq(chatKey))).thenReturn(Mono.just("{}"));
        when(redisTemplate.delete(eq(chatKey))).thenReturn(Mono.just(1L));
        // 不同 call_site 的 key 不同：chat 可查、rewrite 不可查
        assertThat(service.get(request("deepseek-v4-pro", "chat")).blockOptional()).isEmpty();
        verify(valueOps).get(eq(chatKey));
        verify(valueOps, never()).get(eq(rewriteKey));
    }

    private String cacheKeyOf(String model, String callSite) {
        // 复刻服务端 key 构造，验证 call_site 参与散列（temperature 默认 0.7）
        String raw = model + "|" + callSite + "|user:你好|0.7";
        return md5(raw);
    }

    private String md5(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
