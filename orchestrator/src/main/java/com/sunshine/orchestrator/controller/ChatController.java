package com.sunshine.orchestrator.controller;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.agent.SunshineAgent;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationJobFactory;
import com.sunshine.orchestrator.generation.GenerationProperties;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.generation.GenerationStatus;
import com.sunshine.orchestrator.generation.GenerationStreamService;
import com.sunshine.orchestrator.generation.StreamEvent;
import com.sunshine.orchestrator.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final IntentRouter intentRouter;
    private final LlmGatewayClient llmGateway;
    private final SunshineAgent sunshineAgent;
    private final ConversationService conversationService;
    private final GenerationFlushScheduler flushScheduler;
    private final GenerationProperties generationProperties;

    @Autowired(required = false)
    private GenerationJobFactory jobFactory;

    @Autowired(required = false)
    private GenerationRegistry registry;

    @Autowired(required = false)
    private GenerationStreamService streamService;

    @Value("${agent.history.max-messages:20}")
    private int maxHistoryMessages;

    @Value("${agent.generation.flush-interval-ms:500}")
    private long flushIntervalMs;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatMessage msg,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        validateRequest(msg);

        if (StringUtils.hasText(msg.getResumeMessageId())) {
            return handleResume(msg, userId, tenantId);
        }
        return handleNewMessage(msg, userId, tenantId);
    }

    private void validateRequest(ChatMessage msg) {
        boolean hasContent = StringUtils.hasText(msg.getContent());
        boolean hasResume = StringUtils.hasText(msg.getResumeMessageId());
        if (hasContent == hasResume) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "content 与 resumeMessageId 必须二选一");
        }
    }

    private Flux<ServerSentEvent<String>> handleNewMessage(
            ChatMessage msg, String userId, String tenantId) {

        return ReactiveBlocking.call(() -> prepareNewMessage(msg, userId, tenantId))
                .flatMapMany(ctx -> {
                    if (jobFactory != null && streamService != null && registry != null) {
                        return handleNewMessageWithRedis(ctx);
                    }
                    Flux<String> chunkFlux = resolveChunkFlux(ctx);
                    return wrapStream(ctx, chunkFlux, false);
                });
    }

    private Flux<ServerSentEvent<String>> handleNewMessageWithRedis(StreamContext ctx) {
        String generationId = streamService.createGeneration(
                ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), ctx.intent());

        if (!registry.tryLockMessage(ctx.assistantMsgId(), generationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "message 已在生成中");
        }

        GenerationJob job = jobFactory.create(
                generationId, ctx.assistantMsgId(), ctx.conversationId(),
                ctx.userId(), ctx.tenantId(), ctx.intent());
        registry.register(job);

        StringBuilder buffer = new StringBuilder();
        Consumer<String> flushPartial = content ->
                flushScheduler.flushPartial(ctx.assistantMsgId(), content);
        Runnable onComplete = () -> Mono.fromRunnable(() -> {
                    maybeUpdateTitle(ctx);
                    registry.remove(generationId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        Consumer<Throwable> onError = error -> {
            Mono.fromRunnable(() -> registry.remove(generationId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            log.error("[Orchestrator] generation 异常 genId={}", generationId, error);
        };

        job.start(resolveChunkFlux(ctx), buffer, flushPartial, onComplete, onError);

        return sseFluxFromRedis(ctx, generationId, job);
    }

    private Flux<ServerSentEvent<String>> sseFluxFromRedis(
            StreamContext ctx, String generationId, GenerationJob job) {

        Flux<ServerSentEvent<String>> meta = Flux.just(
                sse(flushScheduler.metaConversation(ctx.conversationId())),
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), MessageStatus.STREAMING, false)),
                sse(flushScheduler.metaGeneration(generationId, ctx.assistantMsgId()))
        );

        List<StreamEvent> existing = streamService.readFrom(
                generationId, 0, generationProperties.maxBufferChunks());
        AtomicLong lastEmittedSeq = new AtomicLong(
                existing.stream().mapToLong(StreamEvent::seq).max().orElse(0));

        Flux<ServerSentEvent<String>> historical = Flux.fromIterable(existing)
                .doOnNext(e -> lastEmittedSeq.updateAndGet(cur -> Math.max(cur, e.seq())))
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        long subscribeAfter = lastEmittedSeq.get();
        Flux<ServerSentEvent<String>> live = streamService.subscribe(generationId, subscribeAfter)
                .doOnNext(e -> lastEmittedSeq.set(e.seq()))
                .takeUntilOther(
                        Flux.interval(Duration.ofMillis(50))
                                .filter(t -> isCaughtUpAndTerminal(generationId, lastEmittedSeq.get()))
                                .take(1))
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> Flux.just(
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), resolveFinalStatus(generationId), false))));

        return Flux.concat(meta, historical, live, done)
                .doOnSubscribe(s -> job.onSubscriberAttached())
                .doOnCancel(job::onSubscriberGone)
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成 conv={} gen={}",
                        ctx.conversationId(), generationId))
                .doOnError(e -> log.error("[Orchestrator] SSE 异常 genId={}", generationId, e));
    }

    private boolean isCaughtUpAndTerminal(String generationId, long lastEmittedSeq) {
        return streamService.getMeta(generationId)
                .map(meta -> {
                    GenerationStatus status = meta.status();
                    boolean terminal = status == GenerationStatus.COMPLETED
                            || status == GenerationStatus.FAILED
                            || status == GenerationStatus.INTERRUPTED;
                    return terminal && lastEmittedSeq >= meta.lastSeq();
                })
                .orElse(false);
    }

    private String resolveFinalStatus(String generationId) {
        return streamService.getMeta(generationId)
                .map(meta -> switch (meta.status()) {
                    case FAILED -> MessageStatus.FAILED;
                    case INTERRUPTED -> MessageStatus.INTERRUPTED;
                    default -> MessageStatus.COMPLETED;
                })
                .orElse(MessageStatus.COMPLETED);
    }

    private Flux<ServerSentEvent<String>> handleResume(
            ChatMessage msg, String userId, String tenantId) {

        return ReactiveBlocking.call(() -> prepareResume(msg, userId, tenantId))
                .flatMapMany(ctx -> {
                    Flux<String> chunkFlux = llmGateway.streamContinue(
                            ctx.history(), ctx.userContent(), ctx.existingContent());
                    return wrapStream(ctx, chunkFlux, true);
                });
    }

    private Flux<ServerSentEvent<String>> wrapStream(
            StreamContext ctx, Flux<String> chunkFlux, boolean resume) {

        StringBuilder buffer = new StringBuilder(resume ? ctx.existingContent() : "");
        var appender = flushScheduler.createChunkAppender(buffer, ctx.assistantMsgId(), flushIntervalMs);

        Flux<ServerSentEvent<String>> meta = Flux.just(
                sse(flushScheduler.metaConversation(ctx.conversationId())),
                sse(flushScheduler.metaMessage(ctx.assistantMsgId(), MessageStatus.STREAMING, resume))
        );

        Flux<ServerSentEvent<String>> chunks = chunkFlux
                .doOnNext(appender)
                .map(this::sse);

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            Mono.fromRunnable(() -> {
                        flushScheduler.commitFinal(ctx.assistantMsgId(), buffer.toString(), MessageStatus.COMPLETED);
                        maybeUpdateTitle(ctx);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            return Flux.just(sse(flushScheduler.metaMessage(
                    ctx.assistantMsgId(), MessageStatus.COMPLETED, resume)));
        });

        return Flux.concat(meta, chunks, done)
                .doOnCancel(() -> Mono.fromRunnable(() ->
                                flushScheduler.commitFinal(
                                        ctx.assistantMsgId(), buffer.toString(), MessageStatus.INTERRUPTED))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe())
                .doOnError(e -> Mono.fromRunnable(() ->
                                flushScheduler.commitFinal(
                                        ctx.assistantMsgId(), buffer.toString(), MessageStatus.FAILED))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe())
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成 conv={}", ctx.conversationId()))
                .doOnError(e -> log.error("[Orchestrator] 异常", e));
    }

    private StreamContext prepareNewMessage(ChatMessage msg, String userId, String tenantId) {
        ChatConversationEntity conv = resolveConversation(msg.getConversationId(), userId, tenantId);
        conversationService.appendMessage(conv.getId(), "user", msg.getContent(), MessageStatus.COMPLETED);
        ChatMessageEntity assistant = conversationService.appendMessage(
                conv.getId(), "assistant", "", MessageStatus.STREAMING);

        List<ChatMessageEntity> dbHistory = conversationService.loadHistory(conv.getId(), maxHistoryMessages);
        List<ChatTurn> history = toTurnsExcludingStreamingAssistant(dbHistory, assistant.getId());
        String userContent = msg.getContent();

        return new StreamContext(
                conv.getId(),
                assistant.getId(),
                conv.getTitle(),
                userContent,
                history,
                "",
                null,
                true,
                userId,
                tenantId
        );
    }

    private StreamContext prepareResume(ChatMessage msg, String userId, String tenantId) {
        ChatMessageEntity assistant = conversationService.getMessageOwned(
                msg.getResumeMessageId(), userId, tenantId);
        conversationService.validateResumeAllowed(assistant, userId, tenantId);
        conversationService.incrementResumeCount(assistant.getId());
        conversationService.updateMessageContent(
                assistant.getId(), assistant.getContent(), MessageStatus.STREAMING);

        List<ChatMessageEntity> historyEntities = conversationService.loadHistoryForResume(
                assistant.getConversationId(), assistant);
        List<ChatTurn> history = historyEntities.stream()
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .collect(Collectors.toList());

        String userContent = historyEntities.stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(ChatMessageEntity::getContent)
                .orElse("");

        return new StreamContext(
                assistant.getConversationId(),
                assistant.getId(),
                null,
                userContent,
                history,
                assistant.getContent(),
                assistant.getIntent(),
                false,
                userId,
                tenantId
        );
    }

    private Flux<String> resolveChunkFlux(StreamContext ctx) {
        if (ctx.intent() != null) {
            if ("knowledge".equals(ctx.intent())) {
                return sunshineAgent.chat(ctx.history(), ctx.userContent(), "", "");
            }
            return llmGateway.streamContinue(ctx.history(), ctx.userContent(), ctx.existingContent());
        }

        return intentRouter.classify(ctx.userContent())
                .flatMapMany(intent -> {
                    Mono<Void> saveIntent = Mono.fromRunnable(() ->
                                    conversationService.updateMessageIntent(ctx.assistantMsgId(), intent))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then();
                    return saveIntent.thenMany(selectStreamByIntent(intent, ctx));
                });
    }

    private Flux<String> selectStreamByIntent(String intent, StreamContext ctx) {
        if ("knowledge".equals(intent)) {
            log.info("[Orchestrator] → Agent 路径（知识库检索）");
            return sunshineAgent.chat(ctx.history(), ctx.userContent(), "", "");
        }
        log.info("[Orchestrator] → 直连流式（简单对话）");
        return llmGateway.streamWithHistory(ctx.history(), ctx.userContent());
    }

    private ChatConversationEntity resolveConversation(String conversationId, String userId, String tenantId) {
        if (!StringUtils.hasText(conversationId)) {
            return conversationService.create(userId, tenantId);
        }
        return conversationService.getOwned(conversationId, userId, tenantId);
    }

    private List<ChatTurn> toTurnsExcludingStreamingAssistant(
            List<ChatMessageEntity> messages, String streamingAssistantId) {
        return messages.stream()
                .filter(m -> !m.getId().equals(streamingAssistantId))
                .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
    }

    private void maybeUpdateTitle(StreamContext ctx) {
        if (!ctx.autoTitle() || ctx.conversationTitle() == null || !"新对话".equals(ctx.conversationTitle())) {
            return;
        }
        String title = ctx.userContent().length() > 28
                ? ctx.userContent().substring(0, 28)
                : ctx.userContent();
        Mono.fromRunnable(() -> conversationService.updateTitle(
                        ctx.conversationId(), ctx.userId(), ctx.tenantId(), title))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .data(data)
                .build();
    }

    private ServerSentEvent<String> sseWithId(String id, String data) {
        return ServerSentEvent.<String>builder()
                .id(id)
                .data(data)
                .build();
    }

    private record StreamContext(
            String conversationId,
            String assistantMsgId,
            String conversationTitle,
            String userContent,
            List<ChatTurn> history,
            String existingContent,
            String intent,
            boolean autoTitle,
            String userId,
            String tenantId
    ) {
    }
}
