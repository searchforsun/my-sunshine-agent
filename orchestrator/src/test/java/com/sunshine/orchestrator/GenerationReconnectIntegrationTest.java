package com.sunshine.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.dto.ConversationDetailDto;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.testsupport.EmbeddedRedisTestConfig;
import com.sunshine.testsupport.SseEventTestSupport;
import com.sunshine.orchestrator.generation.GenerationMeta;
import com.sunshine.orchestrator.generation.GenerationStatus;
import com.sunshine.orchestrator.generation.GenerationStreamService;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.model.ChatMessage;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 1.6 Track G — Redis SSE 无感重连集成测试。
 * 仅本类启用 embedded-redis；不影响 ConversationIntegrationTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(EmbeddedRedisTestConfig.class)
class GenerationReconnectIntegrationTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String TENANT = "default";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GenerationStreamService streamService;

    @Autowired
    private GenerationRegistry registry;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private ChatConversationRepository conversationRepository;

    @LocalServerPort
    private int port;

    @MockBean
    private LlmGatewayClient llmGateway;

    @MockBean
    private IntentRouter intentRouter;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", EmbeddedRedisTestConfig::redisHost);
        registry.add("spring.data.redis.port", EmbeddedRedisTestConfig::redisPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("agent.generation.reconnect-block-ms", () -> 100);
    }

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        Set<String> keys = redis.keys("sunshine:gen:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        when(intentRouter.classifyPlan(anyString())).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.SIMPLE_LLM, null, Map.of(), "test")));
        when(llmGateway.streamContinue(any(MemoryContext.class), anyString(), anyString()))
                .thenReturn(Flux.just(StreamToken.content(" continued")));
    }

    @AfterEach
    void tearDownGenerations() {
        registry.cancelAll();
    }

    @Test
    @DisplayName("generationBuffersWhileNoSubscriber — disconnect 后 Redis lastSeq 仍增长")
    void generationBuffersWhileNoSubscriber() throws Exception {
        when(llmGateway.streamWithMemory(any(MemoryContext.class), eq("buffer test")))
                .thenReturn(Flux.range(1, 100)
                        .delayElements(Duration.ofMillis(40))
                        .map(i -> StreamToken.content("t")));

        String convId = createConversation(ALICE);
        AtomicReference<String> generationId = new AtomicReference<>();
        CountDownLatch metaReady = new CountDownLatch(1);

        WebClient client = webClient();
        ChatMessage req = chatRequest(convId, "buffer test");

        Disposable disposable = client.post()
                .uri("/chat/stream")
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(ev -> {
                    if (captureGenerationId(ev, generationId)) {
                        metaReady.countDown();
                    }
                })
                .subscribe();

        assertThat(metaReady.await(10, TimeUnit.SECONDS)).isTrue();
        long disconnectSeq = streamService.getMeta(generationId.get())
                .map(GenerationMeta::lastSeq)
                .orElse(0L);
        disposable.dispose();

        assertThat(generationId.get()).isNotNull();
        awaitLastSeqBeyond(generationId.get(), Math.max(disconnectSeq, 5L), 20_000);
    }

    @Test
    @DisplayName("reconnectAfterSeq_resumesStream — afterSeq=10 只收到 seq>10")
    void reconnectAfterSeq_resumesStream() throws Exception {
        when(llmGateway.streamWithMemory(any(MemoryContext.class), eq("reconnect test")))
                .thenReturn(Flux.range(1, 25)
                        .delayElements(Duration.ofMillis(30))
                        .map(i -> StreamToken.content("c" + i)));

        String convId = createConversation(ALICE);
        AtomicReference<String> generationId = new AtomicReference<>();
        CountDownLatch metaReady = new CountDownLatch(1);

        WebClient client = webClient();
        ChatMessage req = chatRequest(convId, "reconnect test");

        Disposable disposable = client.post()
                .uri("/chat/stream")
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(ev -> {
                    if (captureGenerationId(ev, generationId)) {
                        metaReady.countDown();
                    }
                })
                .subscribe();

        assertThat(metaReady.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(generationId.get()).isNotNull();
        awaitGenerationCompleted(generationId.get(), 15, 30_000);
        disposable.dispose();

        List<Long> reconnectSeqs = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chat/stream/" + generationId.get())
                        .queryParam("afterSeq", 10)
                        .build())
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .take(Duration.ofSeconds(10))
                .flatMap(ev -> SseEventTestSupport.contentSeq(objectMapper, ev).map(Mono::just).orElse(Mono.empty()))
                .collectList()
                .block(Duration.ofSeconds(15));

        assertThat(reconnectSeqs).isNotNull().isNotEmpty();
        assertThat(reconnectSeqs).allMatch(seq -> seq > 10);
    }

    @Test
    @DisplayName("reconnectWhenInterrupted_returns410 — cancel 后 GET reconnect → 410")
    void reconnectWhenInterrupted_returns410() throws Exception {
        when(llmGateway.streamWithMemory(any(MemoryContext.class), eq("cancel test")))
                .thenReturn(Flux.range(1, 100)
                        .delayElements(Duration.ofMillis(50))
                        .map(i -> StreamToken.content("x")));

        String convId = createConversation(ALICE);
        AtomicReference<String> generationId = new AtomicReference<>();
        CountDownLatch metaReady = new CountDownLatch(1);

        WebClient client = webClient();
        ChatMessage req = chatRequest(convId, "cancel test");

        Disposable disposable = client.post()
                .uri("/chat/stream")
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(ev -> {
                    if (captureGenerationId(ev, generationId)) {
                        metaReady.countDown();
                    }
                })
                .subscribe();

        assertThat(metaReady.await(10, TimeUnit.SECONDS)).isTrue();
        disposable.dispose();

        webTestClient.post()
                .uri("/generations/{id}/cancel", generationId.get())
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chat/stream/" + generationId.get())
                        .queryParam("afterSeq", 0)
                        .build())
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isEqualTo(410);
    }

    @Test
    @DisplayName("reconnect410ThenResume_succeeds — cancel → reconnect 410 → resume 200")
    void reconnect410ThenResume_succeeds() throws Exception {
        when(llmGateway.streamWithMemory(any(MemoryContext.class), eq("resume combo")))
                .thenReturn(Flux.range(1, 100)
                        .delayElements(Duration.ofMillis(50))
                        .map(i -> StreamToken.content("p")));

        String convId = createConversation(ALICE);
        AtomicReference<String> generationId = new AtomicReference<>();
        CountDownLatch metaReady = new CountDownLatch(1);
        CountDownLatch chunks = new CountDownLatch(3);

        WebClient client = webClient();
        ChatMessage req = chatRequest(convId, "resume combo");

        Disposable disposable = client.post()
                .uri("/chat/stream")
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(ev -> {
                    if (captureGenerationId(ev, generationId)) {
                        metaReady.countDown();
                    }
                    if (SseEventTestSupport.isContentChunk(objectMapper, ev)) {
                        chunks.countDown();
                    }
                })
                .subscribe();

        assertThat(metaReady.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(chunks.await(10, TimeUnit.SECONDS)).isTrue();
        disposable.dispose();

        webTestClient.post()
                .uri("/generations/{id}/cancel", generationId.get())
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chat/stream/" + generationId.get())
                        .queryParam("afterSeq", 0)
                        .build())
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isEqualTo(410);

        String messageId = streamService.getMeta(generationId.get())
                .orElseThrow()
                .messageId();

        ChatMessage resume = new ChatMessage();
        resume.setConversationId(convId);
        resume.setResumeMessageId(messageId);

        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(resume)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockLast(Duration.ofSeconds(15));

        ConversationDetailDto.MessageDto assistant = awaitAssistantStatus(
                convId, messageId, MessageStatus.COMPLETED, 50);
        assertThat(assistant.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(assistant.getContent()).contains("p").contains("continued");
        verify(llmGateway).streamContinue(any(MemoryContext.class), anyString(), anyString());
    }

    private ConversationDetailDto.MessageDto awaitAssistantStatus(
            String convId, String messageId, String expectedStatus, int attempts) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            ConversationDetailDto detail = webTestClient.get()
                    .uri("/conversations/{id}", convId)
                    .header("x-user-id", ALICE)
                    .header("x-tenant-id", TENANT)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(ConversationDetailDto.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(detail).isNotNull();
            Optional<ConversationDetailDto.MessageDto> assistant = detail.getMessages().stream()
                    .filter(m -> messageId.equals(m.getId()))
                    .findFirst();
            if (assistant.isPresent() && expectedStatus.equals(assistant.get().getStatus())) {
                return assistant.get();
            }
            Thread.sleep(200);
        }
        ConversationDetailDto detail = webTestClient.get()
                .uri("/conversations/{id}", convId)
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ConversationDetailDto.class)
                .returnResult()
                .getResponseBody();
        return detail.getMessages().stream()
                .filter(m -> messageId.equals(m.getId()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("reconnectForbiddenUser_returns404 — bob 重连 alice generation → 404")
    void reconnectForbiddenUser_returns404() throws Exception {
        when(llmGateway.streamWithMemory(any(MemoryContext.class), eq("forbidden test")))
                .thenReturn(Flux.range(1, 50)
                        .delayElements(Duration.ofMillis(40))
                        .map(i -> StreamToken.content("y")));

        String convId = createConversation(ALICE);
        AtomicReference<String> generationId = new AtomicReference<>();
        CountDownLatch metaReady = new CountDownLatch(1);

        WebClient client = webClient();
        ChatMessage req = chatRequest(convId, "forbidden test");

        Disposable disposable = client.post()
                .uri("/chat/stream")
                .header("x-user-id", ALICE)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(ev -> {
                    if (captureGenerationId(ev, generationId)) {
                        metaReady.countDown();
                    }
                })
                .subscribe();

        assertThat(metaReady.await(10, TimeUnit.SECONDS)).isTrue();
        disposable.dispose();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chat/stream/" + generationId.get())
                        .queryParam("afterSeq", 0)
                        .build())
                .header("x-user-id", BOB)
                .header("x-tenant-id", TENANT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── helpers ──

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private WebClient webClient() {
        return WebClient.builder().baseUrl(baseUrl()).build();
    }

    private ChatMessage chatRequest(String convId, String content) {
        ChatMessage req = new ChatMessage();
        req.setConversationId(convId);
        req.setContent(content);
        return req;
    }

    private String createConversation(String userId) {
        return webTestClient.post()
                .uri("/conversations")
                .header("x-user-id", userId)
                .header("x-tenant-id", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody()
                .get("id")
                .toString();
    }

    private boolean captureGenerationId(ServerSentEvent<String> ev, AtomicReference<String> holder) {
        if (holder.get() != null) {
            return false;
        }
        String data = ev.data();
        if (data == null || !data.contains("\"type\":\"generation\"")) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(data);
            if ("generation".equals(node.path("type").asText())) {
                holder.set(node.path("id").asText());
                return true;
            }
        } catch (Exception ignored) {
            // not generation meta
        }
        return false;
    }

    private void awaitLastSeqBeyond(String generationId, long beyond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<GenerationMeta> meta = streamService.getMeta(generationId);
            if (meta.isPresent() && meta.get().lastSeq() > beyond) {
                assertThat(meta.get().lastSeq()).isGreaterThan(beyond);
                return;
            }
            Thread.sleep(200);
        }
        long lastSeq = streamService.getMeta(generationId).map(GenerationMeta::lastSeq).orElse(-1L);
        fail("lastSeq did not grow beyond " + beyond + ", actual=" + lastSeq);
    }

    private void awaitGenerationCompleted(String generationId, long minLastSeq, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<GenerationMeta> meta = streamService.getMeta(generationId);
            if (meta.isPresent()
                    && meta.get().status() == GenerationStatus.COMPLETED
                    && meta.get().lastSeq() >= minLastSeq) {
                return;
            }
            Thread.sleep(200);
        }
        GenerationMeta meta = streamService.getMeta(generationId).orElse(null);
        fail("generation did not complete: meta=" + meta);
    }
}
