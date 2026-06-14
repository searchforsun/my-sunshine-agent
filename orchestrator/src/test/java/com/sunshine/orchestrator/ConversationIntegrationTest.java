package com.sunshine.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.dto.ConversationDetailDto;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.model.ChatMessage;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.testsupport.SseEventTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 1.5 会话 MVP 集成测试 — H2 + Mock LLM，不依赖外部服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ConversationIntegrationTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String TENANT = "default";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LlmGatewayClient llmGateway;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private ChatConversationRepository conversationRepository;

    @LocalServerPort
    private int port;

    @MockBean
    private IntentRouter intentRouter;

    @BeforeEach
    void setUpMocks() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        when(intentRouter.classify(anyString())).thenReturn(Mono.just("simple"));
        when(llmGateway.streamWithHistory(anyList(), anyString()))
                .thenAnswer(inv -> {
                    String userMsg = inv.getArgument(1);
                    return Flux.just(StreamToken.content("reply:" + userMsg));
                });
        when(llmGateway.streamContinue(anyList(), anyString(), anyString()))
                .thenReturn(Flux.just(StreamToken.content(" continued")));
    }

    @Test
    @DisplayName("serviceLayer_getMessages — 直接验证 JPA 读写")
    void serviceLayer_getMessages() {
        ChatConversationEntity conv = conversationService.create(ALICE, TENANT);
        conversationService.appendMessage(conv.getId(), "user", "hi", MessageStatus.COMPLETED);
        assertThat(conversationService.getMessages(conv.getId(), ALICE, TENANT)).hasSize(1);
    }

    @Test
    @DisplayName("createConversation_andAppendMessages — 两轮对话后 messages >= 4")
    void createConversation_andAppendMessages() {
        String convId = createConversation(ALICE);

        streamChat(ALICE, convId, "我叫小明");
        streamChat(ALICE, convId, "我叫什么？");

        ConversationDetailDto detail = getConversation(ALICE, convId);
        assertThat(detail.getMessages()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(detail.getMessages().stream().filter(m -> "user".equals(m.getRole())).count())
                .isGreaterThanOrEqualTo(2);
        assertThat(detail.getMessages().stream().filter(m -> "assistant".equals(m.getRole())).count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("multiTurnContext_simpleIntent — 第二轮携带第一轮 history")
    void multiTurnContext_simpleIntent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> historyCaptor = ArgumentCaptor.forClass(List.class);

        String convId = createConversation(ALICE);
        streamChat(ALICE, convId, "我叫小明");
        streamChat(ALICE, convId, "我叫什么？");

        verify(llmGateway, times(2)).streamWithHistory(historyCaptor.capture(), anyString());
        List<ChatTurn> secondRoundHistory = historyCaptor.getAllValues().get(1);
        assertThat(secondRoundHistory).hasSizeGreaterThanOrEqualTo(2);
        assertThat(secondRoundHistory.stream().anyMatch(t -> t.content().contains("小明"))).isTrue();
    }

    @Test
    @DisplayName("threeTurnContext_simpleIntent — 三轮追问 history 连贯")
    void threeTurnContext_simpleIntent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> historyCaptor = ArgumentCaptor.forClass(List.class);

        String convId = createConversation(ALICE);
        streamChat(ALICE, convId, "我叫小明，在上海工作");
        streamChat(ALICE, convId, "我在哪个城市？");
        streamChat(ALICE, convId, "我叫什么名字？");

        verify(llmGateway, times(3)).streamWithHistory(historyCaptor.capture(), anyString());
        List<ChatTurn> thirdRoundHistory = historyCaptor.getAllValues().get(2);
        assertThat(thirdRoundHistory).hasSizeGreaterThanOrEqualTo(4);
        assertThat(thirdRoundHistory.stream().anyMatch(t -> t.content().contains("小明"))).isTrue();
        assertThat(thirdRoundHistory.stream().anyMatch(t -> t.content().contains("上海"))).isTrue();

        ConversationDetailDto detail = getConversation(ALICE, convId);
        assertThat(detail.getMessages()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("forbiddenAccess_returns404 — 越权访问会话")
    void forbiddenAccess_returns404() {
        String convId = createConversation(ALICE);

        assertThatThrownBy(() -> conversationService.getOwned(convId, BOB, TENANT))
                .isInstanceOf(com.sunshine.orchestrator.conversation.ConversationNotFoundException.class);

        webTestClient.get()
                .uri("/conversations/{id}", convId)
                .header("x-user-id", BOB)
                .header("x-tenant-id", TENANT)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("streamInterrupted_persistsPartial — 中途 cancel 后 partial 入库")
    void streamInterrupted_persistsPartial() throws Exception {
        when(llmGateway.streamWithHistory(anyList(), eq("long stream")))
                .thenReturn(Flux.range(1, 100)
                        .delayElements(Duration.ofMillis(40))
                        .map(i -> StreamToken.content("t")));

        String convId = createConversation(ALICE);

        WebClient client = WebClient.builder()
                .baseUrl(baseUrl())
                .build();

        ChatMessage req = new ChatMessage();
        req.setConversationId(convId);
        req.setContent("long stream");

        CountDownLatch chunks = new CountDownLatch(5);
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
                    if (SseEventTestSupport.isContentChunk(objectMapper, ev)) {
                        chunks.countDown();
                    }
                })
                .subscribe();

        assertThat(chunks.await(10, TimeUnit.SECONDS)).isTrue();
        disposable.dispose();

        ConversationDetailDto.MessageDto lastAssistant = null;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(200);
            ConversationDetailDto detail = getConversation(ALICE, convId);
            lastAssistant = detail.getMessages().stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .reduce((a, b) -> b)
                    .orElseThrow();
            if (MessageStatus.INTERRUPTED.equals(lastAssistant.getStatus())
                    && !lastAssistant.getContent().isEmpty()) {
                break;
            }
        }

        assertThat(lastAssistant).isNotNull();
        assertThat(lastAssistant.getStatus()).isEqualTo(MessageStatus.INTERRUPTED);
        assertThat(lastAssistant.getContent()).isNotEmpty();
    }

    @Test
    @DisplayName("resumeContinue_appendsContent — interrupted 续传后 content 变长且 completed")
    void resumeContinue_appendsContent() throws InterruptedException {
        ChatConversationEntity conv = conversationService.create(ALICE, TENANT);
        conversationService.appendMessage(conv.getId(), "user", "hello", MessageStatus.COMPLETED);
        ChatMessageEntity assistant = conversationService.appendMessage(
                conv.getId(), "assistant", "partial", MessageStatus.INTERRUPTED);
        conversationService.updateMessageIntent(assistant.getId(), "simple");

        streamResume(ALICE, conv.getId(), assistant.getId());

        ConversationDetailDto.MessageDto last = awaitMessageStatus(
                ALICE, conv.getId(), assistant.getId(), MessageStatus.COMPLETED, 50);

        assertThat(last.getContent()).contains("partial").contains("continued");
        assertThat(last.getStatus()).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("resumeKnowledgeIntent_returns409 — knowledge 意图不可续传")
    void resumeKnowledgeIntent_returns409() {
        ChatConversationEntity conv = conversationService.create(ALICE, TENANT);
        conversationService.appendMessage(conv.getId(), "user", "查制度", MessageStatus.COMPLETED);
        ChatMessageEntity assistant = conversationService.appendMessage(
                conv.getId(), "assistant", "half answer", MessageStatus.INTERRUPTED);
        conversationService.updateMessageIntent(assistant.getId(), "knowledge");

        assertThat(conversationService.getMessageOwned(assistant.getId(), ALICE, TENANT).getIntent())
                .isEqualTo("knowledge");
        assertThatThrownBy(() -> conversationService.validateResumeAllowed(
                conversationService.getMessageOwned(assistant.getId(), ALICE, TENANT), ALICE, TENANT))
                .isInstanceOf(com.sunshine.orchestrator.conversation.ResumeNotAllowedException.class);

        ChatMessage resume = new ChatMessage();
        resume.setConversationId(conv.getId());
        resume.setResumeMessageId(assistant.getId());

        int status = postStreamStatus(ALICE, resume);
        assertThat(status).isEqualTo(409);
    }

    @Test
    @DisplayName("concurrentResume_returns409 — 同一条消息并行续传一条 409")
    void concurrentResume_returns409() throws Exception {
        when(llmGateway.streamContinue(anyList(), anyString(), anyString()))
                .thenReturn(Flux.just(StreamToken.content("x")).concatWith(Flux.never()));

        ChatConversationEntity conv = conversationService.create(ALICE, TENANT);
        conversationService.appendMessage(conv.getId(), "user", "hi", MessageStatus.COMPLETED);
        ChatMessageEntity assistant = conversationService.appendMessage(
                conv.getId(), "assistant", "part", MessageStatus.INTERRUPTED);
        conversationService.updateMessageIntent(assistant.getId(), "simple");

        ChatMessage resume = new ChatMessage();
        resume.setConversationId(conv.getId());
        resume.setResumeMessageId(assistant.getId());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> streamResumeBlocking(ALICE, resume));
            awaitStreamingStatus(assistant.getId());

            int status = postStreamStatus(ALICE, resume);
            assertThat(status).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── helpers ──

    private String baseUrl() {
        return "http://localhost:" + port;
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

    private void streamChat(String userId, String convId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(convId);
        msg.setContent(content);

        List<String> events = webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(15));

        assertThat(events).isNotNull();
        verify(llmGateway, atLeastOnce()).streamWithHistory(anyList(), eq(content));
    }

    private void streamResume(String userId, String convId, String resumeMessageId) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(convId);
        msg.setResumeMessageId(resumeMessageId);

        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockLast(Duration.ofSeconds(15));
    }

    private void streamResumeBlocking(String userId, ChatMessage msg) {
        webTestClient.post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockLast(Duration.ofSeconds(30));
    }

    private ConversationDetailDto getConversation(String userId, String convId) {
        ConversationDetailDto body = webTestClient.get()
                .uri("/conversations/{id}", convId)
                .header("x-user-id", userId)
                .header("x-tenant-id", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ConversationDetailDto.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private int postStreamStatus(String userId, ChatMessage body) {
        return WebClient.create(baseUrl())
                .post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(Void.class)
                        .thenReturn(resp.statusCode().value()))
                .block(Duration.ofSeconds(10));
    }

    private ConversationDetailDto.MessageDto awaitMessageStatus(
            String userId, String convId, String messageId, String status, int maxAttempts)
            throws InterruptedException {
        ConversationDetailDto.MessageDto last = null;
        for (int i = 0; i < maxAttempts; i++) {
            ConversationDetailDto detail = getConversation(userId, convId);
            last = detail.getMessages().stream()
                    .filter(m -> m.getId().equals(messageId))
                    .findFirst()
                    .orElseThrow();
            if (status.equals(last.getStatus())) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("message " + messageId + " did not reach status " + status
                + ", last=" + (last != null ? last.getStatus() : null));
    }

    private void awaitStreamingStatus(String messageId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (messageRepository.findById(messageId)
                    .map(m -> MessageStatus.STREAMING.equals(m.getStatus()))
                    .orElse(false)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("message did not enter streaming status");
    }
}
