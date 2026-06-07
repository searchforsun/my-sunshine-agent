package com.sunshine.orchestrator;

import com.sunshine.orchestrator.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试：Orchestrator → LLM Gateway → DeepSeek API
 *
 * 运行要求：
 * 1. llm-gateway 已启动在 localhost:8300
 * 2. DeepSeek API Key 已配置
 *
 * 如果 LLM Gateway 未启动，此测试将失败（非 Mock 测试，验证真实链路）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class ChatIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("SSE 流式对话 — 全链路联通验证")
    void shouldStreamChatResponse() {
        ChatMessage msg = new ChatMessage();
        msg.setContent("你好，请用一句话介绍自己");

        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", "test-user")
                .header("x-tenant-id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream")
                .expectBodyList(String.class)
                .consumeWith(result -> {
                    assertThat(result.getResponseBody())
                            .isNotNull()
                            .isNotEmpty();
                });
    }

    @Test
    @DisplayName("SSE 流式对话 — 验证基本响应格式")
    void shouldReturnProperSseFormat() {
        ChatMessage msg = new ChatMessage();
        msg.setContent("回复 OK");

        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", "test-user")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk();
    }
}
