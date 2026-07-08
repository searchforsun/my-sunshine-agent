package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.catalog.ExpertCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.expert.ConsultationSynthesizer;
import com.sunshine.orchestrator.expert.ExpertCollaborationParams;
import com.sunshine.orchestrator.expert.ExpertCoordinatorService;
import com.sunshine.orchestrator.expert.ExpertHubEngine;
import com.sunshine.orchestrator.expert.ExpertRoster;
import com.sunshine.orchestrator.expert.ExpertRoundCoordinatorService;
import com.sunshine.orchestrator.expert.ExpertSpeakCallback;
import com.sunshine.orchestrator.expert.ExpertTimelineSupport;
import com.sunshine.orchestrator.expert.ExpertTranscriptEntry;
import com.sunshine.orchestrator.peer.PeerRunAuditService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.Exceptions;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 多专家协作 — Catalog 驱动 MsgHub + ConsultationSynthesizer */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpertConsultationExecutor {
    /** 专家 Hub 含 agent.call().block() + 远程工具 boundedElastic，不可与后者共用同池 */
    private static final Scheduler EXPERT_HUB_SCHEDULER = Schedulers.fromExecutor(
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));

    private final ExpertCatalogService expertCatalogService;
    private final ExpertCoordinatorService coordinatorService;
    private final ExpertRoundCoordinatorService roundCoordinatorService;
    private final ExpertHubEngine expertHubEngine;
    private final ConsultationSynthesizer consultationSynthesizer;
    private final PeerRunAuditService peerRunAuditService;
    private final ReactExecutor reactExecutor;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        if (ctx.plan() == null || ctx.plan().mode() != ExecutionMode.PEER_COLLAB) {
            return reactExecutor.execute(ctx);
        }
        return Flux.<StreamToken>create(
                        sink -> runConsultation(ctx, sink),
                        FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(EXPERT_HUB_SCHEDULER);
    }

    private void runConsultation(ExecutionStreamContext ctx, FluxSink<StreamToken> sink) {
        Object emitLock = new Object();
        java.util.function.Consumer<StreamToken> emit = token -> {
            if (token == null) {
                return;
            }
            synchronized (emitLock) {
                if (!sink.isCancelled()) {
                    sink.next(token);
                }
            }
        };
        try {
            log.info("[ExpertConsultation] start conv={} msg={}", ctx.conversationId(), ctx.assistantMsgId());
            if (StringUtils.hasText(ctx.assistantMsgId())) {
                StepEventBridge.bindToolAudit(ctx.assistantMsgId(), new StepEventBridge.ToolAuditContext(
                        ctx.conversationId(),
                        ctx.assistantMsgId(),
                        ctx.userId(),
                        ctx.tenantId(),
                        ctx.persistedPlanId(),
                        ctx.kbId()));
            }
            long conveneStartedAt = System.currentTimeMillis();
            emit.accept(ExpertTimelineSupport.conveneRunning(conveneStartedAt));
            String query = resolveQuery(ctx);
            List<String> explicitIds = parseExpertIds(ctx.plan().params());
            ExpertRoster roster = coordinatorService.resolve(explicitIds, query);
            log.info("[ExpertConsultation] roster={} sessionMax={}", roster.expertIds(), roster.sessionMaxRounds());
            List<ExpertCatalogEntry> experts = new ArrayList<>();
            for (String id : roster.expertIds()) {
                experts.add(expertCatalogService.find(id)
                        .orElseThrow(() -> new IllegalStateException("expert missing: " + id)));
            }
            List<String> displayNames = experts.stream().map(ExpertCatalogEntry::displayName).toList();
            emit.accept(ExpertTimelineSupport.conveneDone(conveneStartedAt, displayNames, roster.reason()));
            Map<String, Long> speakStartedAt = new HashMap<>();
            Map<String, Boolean> speakResponding = new HashMap<>();
            ExpertSpeakCallback callback = new ExpertSpeakCallback() {
                @Override
                public void onSpeak(ExpertTranscriptEntry entry, String lifecycle, boolean responding) {
                    String stepId = ExpertTimelineSupport.speakStepId(entry);
                    if ("running".equals(lifecycle)) {
                        long started = System.currentTimeMillis();
                        speakStartedAt.put(stepId, started);
                        speakResponding.put(stepId, responding);
                        emit.accept(ExpertTimelineSupport.speakRunning(entry, responding, started));
                    } else if ("done".equals(lifecycle)) {
                        long started = speakStartedAt.getOrDefault(stepId, System.currentTimeMillis());
                        emit.accept(ExpertTimelineSupport.speakDone(entry, responding, started, entry.content()));
                    }
                }

                @Override
                public void onSpeakDelta(ExpertTranscriptEntry entry, String text) {
                    emit.accept(ExpertTimelineSupport.speakDelta(ExpertTimelineSupport.speakStepId(entry), text));
                }

                @Override
                public void onSpeakActive(ExpertTranscriptEntry entry, String activeText) {
                    String stepId = ExpertTimelineSupport.speakStepId(entry);
                    long started = speakStartedAt.getOrDefault(stepId, System.currentTimeMillis());
                    boolean responding = Boolean.TRUE.equals(speakResponding.get(stepId));
                    emit.accept(ExpertTimelineSupport.speakActive(entry, activeText, responding, started));
                }
            };
            int sessionMax = roster.sessionMaxRounds() != null
                    ? roster.sessionMaxRounds()
                    : roundCoordinatorService.assessSessionMaxRounds(query, roster.expertIds());
            log.info("[ExpertConsultation] roster={} sessionMaxRounds={}",
                    roster.expertIds(), sessionMax);
            ExpertHubEngine.ExpertHubResult hubResult = expertHubEngine.run(
                    experts,
                    query,
                    sessionMax,
                    ctx.assistantMsgId(),
                    callback);
            peerRunAuditService.persistFinal(
                    ctx.conversationId(),
                    ctx.assistantMsgId(),
                    ctx.userId(),
                    ctx.tenantId(),
                    hubResult.runId(),
                    String.join(",", roster.expertIds()),
                    hubResult.transcript());
            consultationSynthesizer.synthesize(query, hubResult.transcript())
                    .doOnNext(emit)
                    .doOnComplete(() -> {
                        synchronized (emitLock) {
                            if (!sink.isCancelled()) {
                                sink.complete();
                            }
                        }
                    })
                    .doOnError(error -> {
                        synchronized (emitLock) {
                            if (!sink.isCancelled()) {
                                sink.error(error);
                            }
                        }
                    })
                    .subscribe();
        } catch (Exception e) {
            if (isBenignInterrupt(e)) {
                log.info("[ExpertConsultationExecutor] 协作已中断: {}", e.getMessage());
                synchronized (emitLock) {
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                }
                return;
            }
            log.warn("[ExpertConsultationExecutor] 协作失败，降级 react: {}", e.getMessage());
            emit.accept(ExpertTimelineSupport.conveneDone(System.currentTimeMillis(), List.of(), "协作失败，已降级"));
            reactExecutor.execute(ctx.withPlan(ExecutionPlan.reactFallback("expert-collab failed")))
                    .doOnNext(emit)
                    .doOnComplete(() -> {
                        synchronized (emitLock) {
                            if (!sink.isCancelled()) {
                                sink.complete();
                            }
                        }
                    })
                    .doOnError(error -> {
                        synchronized (emitLock) {
                            if (!sink.isCancelled()) {
                                sink.error(error);
                            }
                        }
                    })
                    .subscribe();
        }
    }

    private static String resolveQuery(ExecutionStreamContext ctx) {
        Map<String, String> params = ctx.plan() != null ? ctx.plan().params() : null;
        if (params != null && StringUtils.hasText(params.get(ExpertCollaborationParams.EFFECTIVE_QUERY))) {
            return params.get(ExpertCollaborationParams.EFFECTIVE_QUERY).strip();
        }
        return ctx.userContent();
    }

    private static List<String> parseExpertIds(Map<String, String> params) {
        if (params == null) {
            return List.of();
        }
        String raw = params.get(ExpertCollaborationParams.EXPERT_IDS);
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static boolean isBenignInterrupt(Throwable error) {
        if (error == null) {
            return false;
        }
        if (Exceptions.isCancel(error) || error instanceof InterruptedException) {
            return true;
        }
        String message = error.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("interrupted") || lower.contains("cancel")) {
                return true;
            }
        }
        return isBenignInterrupt(error.getCause());
    }
}
