package com.sunshine.orchestrator.generation;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.DecisionRegistry;
import com.sunshine.orchestrator.agent.ResolveDecisionRequest;
import com.sunshine.orchestrator.agent.SpawnRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.conversation.MessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationStreamService streamService;
    private final GenerationRegistry registry;
    private final GenerationFlushScheduler flushScheduler;
    private final GenerationProperties generationProperties;
    private final ConversationService conversationService;
    private final SpawnRunRegistry spawnRunRegistry;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;
    private final AgentSandboxProperties sandboxProperties;
    private final DecisionRegistry decisionRegistry;

    @GetMapping("/generations/{id}")
    public Mono<GenerationStatusResponse> getStatus(
            @PathVariable("id") String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(id)
                    .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
            return GenerationStatusResponse.from(meta);
        });
    }

    @PostMapping("/generations/{id}/cancel")
    public Mono<Map<String, String>> cancel(
            @PathVariable("id") String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            if (registry.get(id).isPresent()) {
                registry.cancel(id);
            } else {
                streamService.getMeta(id).ifPresent(meta -> {
                    registry.releaseBlockingWaitsForMessage(meta.messageId());
                    streamService.updateStatus(id, GenerationStatus.INTERRUPTED);
                    registry.unlockMessage(meta.messageId());
                    conversationService.forceInterruptedIfStreaming(meta.messageId());
                });
            }
            return Map.of("status", GenerationStatus.INTERRUPTED.name());
        });
    }

    /**
     * 用户提交 request_decision 选择题结果（与 cancelSubagent 同属 generation 交互面）。
     */
    @PostMapping("/generations/{id}/decisions/{token}/resolve")
    public Mono<Map<String, Object>> resolveDecision(
            @PathVariable("id") String id,
            @PathVariable("token") String token,
            @RequestBody(required = false) ResolveDecisionRequest body,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(id)
                    .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
            DecisionRegistry.ResolveOutcome outcome = decisionRegistry.resolve(
                    token,
                    body != null ? body.answers() : null,
                    userId,
                    meta.messageId());
            return mapResolveOutcome(outcome);
        });
    }

    /** ResolveOutcome → 成功体或 BizException（供单测断言错误码映射） */
    static Map<String, Object> mapResolveOutcome(DecisionRegistry.ResolveOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> Map.of("accepted", true);
            case INVALID_CHOICE -> throw new BizException(OrchestratorErrorCode.DECISION_INVALID_CHOICE);
            case INVALID_ANSWERS -> throw new BizException(OrchestratorErrorCode.DECISION_INVALID_ANSWERS);
            case INPUT_REQUIRED -> throw new BizException(OrchestratorErrorCode.DECISION_INPUT_REQUIRED);
            case EXPIRED -> throw new BizException(OrchestratorErrorCode.DECISION_EXPIRED);
            case NOT_FOUND, FORBIDDEN -> throw new BizException(OrchestratorErrorCode.DECISION_NOT_FOUND);
        };
    }

    /**
     * 单独取消一次 spawn_subagent（按 runId interrupt），不终止整轮 generation。
     */
    @PostMapping("/generations/{id}/subagents/{runId}/cancel")
    public Mono<Map<String, String>> cancelSubagent(
            @PathVariable("id") String id,
            @PathVariable("runId") String runId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(id)
                    .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
            if (!StringUtils.hasText(runId)) {
                throw new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND);
            }
            String normalizedRunId = runId.strip();
            if (normalizedRunId.startsWith("subagent-")) {
                normalizedRunId = normalizedRunId.substring("subagent-".length());
            }
            SpawnRunRegistry.Handle handle = spawnRunRegistry.get(normalizedRunId);
            if (handle == null) {
                return Map.of("status", "NOT_FOUND", "runId", normalizedRunId);
            }
            if (StringUtils.hasText(handle.messageId())
                    && !handle.messageId().equals(meta.messageId())) {
                throw new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND);
            }
            boolean ok = spawnRunRegistry.cancel(normalizedRunId);
            return Map.of(
                    "status", ok ? "CANCELLED" : "NOT_FOUND",
                    "runId", normalizedRunId);
        });
    }

    /**
     * 单独取消一次可取消沙箱工具（按 toolUseId 或时间线 stepId）。
     * path 可为 AgentScope toolUseId，或以 tool- 开头的 step.id。
     */
    @PostMapping("/generations/{id}/tools/{toolRef}/cancel")
    public Mono<Map<String, String>> cancelTool(
            @PathVariable("id") String id,
            @PathVariable("toolRef") String toolRef,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(id, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(id)
                    .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
            if (!StringUtils.hasText(toolRef)) {
                throw new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND);
            }
            String ref = toolRef.strip();
            String messageId = meta.messageId();
            boolean ok;
            String resolvedId = ref;
            String expandDetail = null;
            if (ref.startsWith("tool-")) {
                CancellableToolRunRegistry.Handle handle = cancellableToolRunRegistry.getByStepId(ref);
                if (handle != null) {
                    if (StringUtils.hasText(handle.messageId())
                            && !handle.messageId().equals(messageId)) {
                        throw new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND);
                    }
                    resolvedId = handle.toolUseId();
                    expandDetail = handle.expandDetail();
                    ok = cancellableToolRunRegistry.cancel(handle.toolUseId());
                } else {
                    ok = cancellableToolRunRegistry.markPendingCancelByStepId(ref, messageId);
                }
            } else {
                CancellableToolRunRegistry.Handle handle = cancellableToolRunRegistry.get(ref);
                if (handle != null) {
                    if (StringUtils.hasText(handle.messageId())
                            && !handle.messageId().equals(messageId)) {
                        throw new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND);
                    }
                    expandDetail = handle.expandDetail();
                    ok = cancellableToolRunRegistry.cancel(ref);
                } else {
                    ok = cancellableToolRunRegistry.markPendingCancel(ref, messageId);
                }
            }
            // 终态单写：挂在 main-* bridge（session 键），勿用 assistant messageId（会静默丢弃）
            if (ok) {
                String after = StringUtils.hasText(sandboxProperties.getCancelAfter())
                        ? sandboxProperties.getCancelAfter().strip() : "已取消";
                final String detail = expandDetail;
                String emitBridge = StepEventBridge.activeMainBridge(messageId);
                if (!StringUtils.hasText(emitBridge)) {
                    emitBridge = StepEventBridge.bridgeIdForToolUse(resolvedId);
                }
                if (StringUtils.hasText(emitBridge)) {
                    if (ref.startsWith("tool-")) {
                        StepEventBridge.emit(emitBridge, session -> session.pause(ref, after, detail));
                    } else {
                        StepEventBridge.emit(emitBridge, session ->
                                session.pauseToolStepForToolUse(ref, after, detail));
                    }
                }
            }
            return Map.of(
                    "status", ok ? "CANCELLED" : "NOT_FOUND",
                    "toolRef", resolvedId);
        });
    }

    @GetMapping(value = "/chat/stream/{generationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reconnectStream(
            @PathVariable String generationId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            streamService.assertOwned(generationId, userId, tenantId);
            GenerationMeta meta = streamService.getMeta(generationId)
                    .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
            if (meta.status() == GenerationStatus.INTERRUPTED || meta.status() == GenerationStatus.FAILED) {
                throw new BizException(OrchestratorErrorCode.GENERATION_STOPPED);
            }
            return meta;
        }).flatMapMany(meta -> buildReconnectFlux(meta, generationId, afterSeq));
    }

    private Flux<ServerSentEvent<String>> buildReconnectFlux(
            GenerationMeta meta, String generationId, long afterSeq) {

        List<StreamEvent> existing = streamService.readFrom(
                generationId, afterSeq, generationProperties.maxBufferChunks());
        AtomicLong lastEmittedSeq = new AtomicLong(
                existing.stream().mapToLong(StreamEvent::seq).max().orElse(afterSeq));

        Flux<ServerSentEvent<String>> historical = Flux.fromIterable(existing)
                .doOnNext(e -> lastEmittedSeq.updateAndGet(cur -> Math.max(cur, e.seq())))
                .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));

        Flux<ServerSentEvent<String>> body;
        if (meta.status() == GenerationStatus.RUNNING || meta.status() == GenerationStatus.CREATED) {
            long subscribeAfter = lastEmittedSeq.get();
            Flux<ServerSentEvent<String>> live = streamService.subscribeToEnd(generationId, subscribeAfter)
                    .map(e -> sseWithId(String.valueOf(e.seq()), e.text()));
            body = Flux.concat(historical, live, doneMeta(meta.messageId(), generationId));
        } else {
            body = Flux.concat(historical, doneMeta(meta.messageId(), generationId));
        }

        return body
                .doOnSubscribe(s -> registry.get(generationId).ifPresent(GenerationJob::onSubscriberAttached))
                .doOnCancel(() -> registry.get(generationId).ifPresent(GenerationJob::onSubscriberGone));
    }

    private Flux<ServerSentEvent<String>> doneMeta(String messageId, String generationId) {
        return Flux.defer(() -> Flux.just(
                sse(flushScheduler.metaMessage(messageId, resolveFinalStatus(generationId), false))));
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

    private ServerSentEvent<String> sseWithId(String id, String data) {
        return ServerSentEvent.<String>builder()
                .id(id)
                .data(data)
                .build();
    }

    private ServerSentEvent<String> sse(String data) {
        return ServerSentEvent.<String>builder()
                .data(data)
                .build();
    }
}
