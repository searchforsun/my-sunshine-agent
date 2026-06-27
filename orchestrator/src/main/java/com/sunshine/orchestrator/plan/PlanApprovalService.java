package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.processing.PlanApprovalLabels;
import com.sunshine.orchestrator.processing.PlanApprovalMeta;
import com.sunshine.orchestrator.processing.PlanApprovalRoundMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 动态 Plan 用户确认 — 阻塞等待「确认执行」或「重新生成」。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanApprovalService {

    private final AgentExecutionProperties executionProperties;
    private final ExecutionPlanStore executionPlanStore;
    @Lazy
    private final GenerationRegistry generationRegistry;
    private final PlanJsonCodec planJsonCodec;

    private final ConcurrentHashMap<String, CompletableFuture<PlanApprovalUserAction>> waiters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenHints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenToPlanToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenToMessageId = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return executionProperties.getPlanWorkflow().getApproval().isEnabled();
    }

    public PlanApprovalWaitResult awaitUserApproval(
            ExecutionStreamContext ctx,
            String planId,
            PlanJson planJson,
            ProcessingTimelineSession session,
            List<PlanApprovalRound> rounds,
            int roundNo) {
        AgentExecutionProperties.PlanWorkflow.Approval cfg =
                executionProperties.getPlanWorkflow().getApproval();
        String token = UUID.randomUUID().toString();
        long expiresAt = Instant.now().plusSeconds(cfg.getTimeoutSec()).toEpochMilli();
        long now = System.currentTimeMillis();
        String chain = PlanTimeline.planChainSummary(planJson);
        List<PlanApprovalRound> mutableRounds = new ArrayList<>(rounds);
        mutableRounds.add(PlanApprovalRound.awaiting(roundNo, chain, now));
        executionPlanStore.markAwaitingApproval(planId, planJson);
        executionPlanStore.saveApprovalRounds(planId, mutableRounds);

        PlanApprovalMeta meta = buildMeta(token, expiresAt, mutableRounds, planJson);
        String detail = PlanTimeline.formatPlanDetail(planId, chain, 0);
        StepMetadata rewriteMeta = StepMetadata.fromRewrite(
                QueryRewriteTrace.plannerOutcome(ctx.assistantMsgId()).orElse(null));
        final StepMetadata baseMeta = rewriteMeta != null
                ? StepMetadata.withRewriteInDetail(rewriteMeta)
                : null;
        StepMetadata stepMeta = StepMetadata.withPlanApproval(baseMeta, meta);

        List<StreamToken> emitted = new ArrayList<>(ProcessingTimelineSupport.run(session, () -> {
            if (roundNo == 1) {
                session.beginPlanAwaitingApproval(detail, stepMeta);
            } else {
                session.updatePlanApproval(stepMeta, PlanApprovalLabels.awaiting());
            }
        }));

        // 须在阻塞前刷 SSE，否则前端拿不到 token，confirm-plan 无法解除等待（同 HITL flushHookTimeline）
        flushToClient(ctx.assistantMsgId(), emitted);

        CompletableFuture<PlanApprovalUserAction> future = new CompletableFuture<>();
        waiters.put(token, future);
        tokenToPlanToken.put(token, token);
        tokenToMessageId.put(token, ctx.assistantMsgId());
        try {
            PlanApprovalUserAction action = future.get(cfg.getTimeoutSec(), TimeUnit.SECONDS);
            log.info("[PlanApproval] planId={} round={} action={}", planId, roundNo, action);
            String hint = tokenHints.remove(token);
            if (action == PlanApprovalUserAction.APPROVED) {
                resolveRound(planId, mutableRounds, mutableRounds.size() - 1,
                        PlanApprovalRound.STATUS_APPROVED, null);
                PlanApprovalMeta approvedMeta = PlanApprovalMeta.approved(
                        toMetaRounds(mutableRounds), meta.planGraph());
                List<StreamToken> resolved = ProcessingTimelineSupport.run(session, () ->
                        session.updatePlanApproval(
                                StepMetadata.withPlanApproval(baseMeta, approvedMeta),
                                PlanApprovalLabels.approved()));
                emitted.addAll(resolved);
                flushToClient(ctx.assistantMsgId(), resolved);
            } else if (action == PlanApprovalUserAction.REGENERATED) {
                resolveRound(planId, mutableRounds, mutableRounds.size() - 1,
                        PlanApprovalRound.STATUS_REGENERATED, hint);
                PlanApprovalMeta regenMeta = PlanApprovalMeta.approved(
                        toMetaRounds(mutableRounds), meta.planGraph());
                List<StreamToken> resolved = ProcessingTimelineSupport.run(session, () ->
                        session.updatePlanApproval(
                                StepMetadata.withPlanApproval(baseMeta, regenMeta),
                                PlanApprovalLabels.regenerating()));
                emitted.addAll(resolved);
                flushToClient(ctx.assistantMsgId(), resolved);
            }
            return new PlanApprovalWaitResult(action, hint, emitted);
        } catch (TimeoutException e) {
            log.warn("[PlanApproval] planId={} round={} 确认超时", planId, roundNo);
            if ("auto_approve".equalsIgnoreCase(cfg.getOnTimeout())) {
                resolveRound(planId, mutableRounds, mutableRounds.size() - 1,
                        PlanApprovalRound.STATUS_APPROVED, null);
                PlanApprovalMeta approvedMeta = PlanApprovalMeta.approved(
                        toMetaRounds(mutableRounds), meta.planGraph());
                List<StreamToken> resolved = ProcessingTimelineSupport.run(session, () ->
                        session.updatePlanApproval(
                                StepMetadata.withPlanApproval(baseMeta, approvedMeta),
                                PlanApprovalLabels.approved()));
                emitted.addAll(resolved);
                flushToClient(ctx.assistantMsgId(), resolved);
                return new PlanApprovalWaitResult(PlanApprovalUserAction.APPROVED, null, emitted);
            }
            resolveRound(planId, mutableRounds, mutableRounds.size() - 1,
                    PlanApprovalRound.STATUS_TIMED_OUT, null);
            PlanApprovalMeta timedMeta = PlanApprovalMeta.approved(
                    toMetaRounds(mutableRounds), meta.planGraph());
            List<StreamToken> timed = ProcessingTimelineSupport.run(session, () ->
                    session.updatePlanApproval(
                            StepMetadata.withPlanApproval(baseMeta, timedMeta),
                            PlanApprovalLabels.timedOut()));
            emitted.addAll(timed);
            flushToClient(ctx.assistantMsgId(), timed);
            return new PlanApprovalWaitResult(PlanApprovalUserAction.TIMED_OUT, null, emitted);
        } catch (Exception e) {
            log.warn("[PlanApproval] planId={} 等待异常: {}", planId, e.getMessage());
            return new PlanApprovalWaitResult(PlanApprovalUserAction.TIMED_OUT, null, emitted);
        } finally {
            waiters.remove(token);
            tokenToPlanToken.remove(token);
            tokenToMessageId.remove(token);
            tokenHints.remove(token);
        }
    }

    /** 用户暂停 generation：解除 Plan 确认阻塞，使 cancel 能完成并释放 message 锁 */
    public void cancelWaitersForMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        String target = messageId.strip();
        waiters.entrySet().removeIf(entry -> {
            String msgId = tokenToMessageId.get(entry.getKey());
            if (!target.equals(msgId)) {
                return false;
            }
            entry.getValue().complete(PlanApprovalUserAction.CANCELLED);
            tokenToMessageId.remove(entry.getKey());
            tokenToPlanToken.remove(entry.getKey());
            tokenHints.remove(entry.getKey());
            return true;
        });
    }

    public void resolveRound(
            String planId,
            List<PlanApprovalRound> rounds,
            int roundIndex,
            String status,
            String userHint) {
        if (roundIndex < 0 || roundIndex >= rounds.size()) {
            return;
        }
        PlanApprovalRound resolved = rounds.get(roundIndex).resolve(status, userHint, System.currentTimeMillis());
        rounds.set(roundIndex, resolved);
        executionPlanStore.saveApprovalRounds(planId, rounds);
    }

    public boolean confirm(String token, String action, String modificationHint) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        CompletableFuture<PlanApprovalUserAction> future = waiters.remove(token);
        if (future == null) {
            log.warn("[PlanApproval] confirm 无效 token={}", token);
            return false;
        }
        tokenToPlanToken.remove(token);
        if ("approve".equalsIgnoreCase(action)) {
            future.complete(PlanApprovalUserAction.APPROVED);
            return true;
        }
        if ("regenerate".equalsIgnoreCase(action)) {
            if (StringUtils.hasText(modificationHint)) {
                tokenHints.put(token, modificationHint.strip());
            }
            future.complete(PlanApprovalUserAction.REGENERATED);
            return true;
        }
        return false;
    }

    private PlanApprovalMeta buildMeta(
            String token,
            long expiresAt,
            List<PlanApprovalRound> rounds,
            PlanJson planJson) {
        return PlanApprovalMeta.awaiting(
                token, expiresAt, toMetaRounds(rounds), planJsonCodec.toGraphMap(planJson));
    }

    private static List<PlanApprovalRoundMeta> toMetaRounds(List<PlanApprovalRound> rounds) {
        return rounds.stream().map(PlanApprovalRoundMeta::from).toList();
    }

    /** 将 plan 确认步骤即时写入 Redis 生成流，供 SSE 拉取 */
    private void flushToClient(String messageId, List<StreamToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        generationRegistry.findByMessageId(messageId).ifPresentOrElse(
                job -> tokens.forEach(job::emitStreamToken),
                () -> log.warn("[PlanApproval] 无活跃 generation messageId={}，确认步骤未即时下发", messageId));
    }
}
