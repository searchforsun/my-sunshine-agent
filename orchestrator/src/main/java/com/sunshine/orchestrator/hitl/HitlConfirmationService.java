package com.sunshine.orchestrator.hitl;

import com.sunshine.common.sandbox.EditDiffBuilder;
import com.sunshine.common.sandbox.FsContentDto;
import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.plan.PendingInteraction;
import com.sunshine.orchestrator.processing.HitlLabels;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.ToolStepIds;
import com.sunshine.orchestrator.sandbox.SandboxIds;
import com.sunshine.orchestrator.sandbox.SandboxSessionHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 写工具 HITL 编排 — token 注册 {@link HitlTokenRegistry}，时间线刷写 {@link HitlTimelineBridge}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlConfirmationService {

    private final AgentHitlProperties properties;
    private final ToolCatalogService toolCatalogService;
    private final HitlTokenRegistry tokenRegistry;
    private final HitlTimelineBridge timelineBridge;
    private final HitlWriteToolSerialGate writeToolSerialGate;
    private final SandboxClient sandboxClient;
    private final AgentSandboxProperties sandboxProperties;

    public boolean awaitConfirmation(String timelineBridgeId, String toolId, Map<String, String> params) {
        String generationMessageId = StepEventBridge.hitlAssistantMessageId(timelineBridgeId);
        if (generationMessageId == null) {
            generationMessageId = timelineBridgeId;
        }
        return awaitConfirmation(timelineBridgeId, generationMessageId, toolId, params);
    }

    public boolean awaitConfirmation(
            String timelineBridgeId,
            String generationMessageId,
            String toolId,
            Map<String, String> params) {
        if (!timelineBridge.isActiveTimelineBridge(timelineBridgeId)) {
            throw new HitlWaitInterruptedException();
        }
        String boundGenerationId = timelineBridge.resolveBoundGenerationId(generationMessageId);
        if (boundGenerationId == null) {
            throw new HitlWaitInterruptedException();
        }
        long epoch = timelineBridge.currentHitlEpoch(generationMessageId);
        return writeToolSerialGate.callExclusive(boundGenerationId, () ->
                awaitConfirmationLocked(timelineBridgeId, generationMessageId, boundGenerationId, epoch, toolId, params));
    }

    private boolean awaitConfirmationLocked(
            String timelineBridgeId,
            String generationMessageId,
            String boundGenerationId,
            long epoch,
            String toolId,
            Map<String, String> params) {
        timelineBridge.ensureHitlEpoch(generationMessageId, epoch);
        String displayName = toolCatalogService.displayName(toolId);
        timelineBridge.flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
        timelineBridge.ensureHitlEpoch(generationMessageId, epoch);
        timelineBridge.progressBridgeToolStep(timelineBridgeId, HitlLabels.pending(displayName),
                generationMessageId, boundGenerationId, epoch);
        timelineBridge.progressBridgeToolStep(timelineBridgeId, HitlLabels.awaiting(),
                generationMessageId, boundGenerationId, epoch);

        HitlTokenRegistry.HitlRegistration reg = tokenRegistry.register(generationMessageId, toolId);
        timelineBridge.ensureHitlEpoch(generationMessageId, epoch);
        String paramsSummary = HitlParamSupport.summarizeParams(params);
        HitlExpandPayload expand = resolveHitlExpand(timelineBridgeId, toolId, params);
        StepEventBridge.emit(timelineBridgeId, session -> session.attachHitlPending(
                reg.token(), displayName, paramsSummary, reg.expiresAt(), expand.expandBody(), expand.editDiff()));
        timelineBridge.flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
        timelineBridge.emitConfirmation(generationMessageId, toolId, params, reg.token(), reg.expiresAt(),
                epoch, boundGenerationId);

        return waitForDecision(reg, toolId, "HITL",
                () -> {
                    timelineBridge.ensureHitlEpoch(generationMessageId, epoch);
                    timelineBridge.ensureGenerationBound(generationMessageId, boundGenerationId);
                },
                approved -> {
                    if (approved) {
                        timelineBridge.progressBridgeToolStep(timelineBridgeId, HitlLabels.approved(displayName),
                                generationMessageId, boundGenerationId, epoch);
                    } else {
                        timelineBridge.progressBridgeToolStep(timelineBridgeId, HitlLabels.denied(),
                                generationMessageId, boundGenerationId, epoch);
                    }
                    String hitlStatus = approved ? HitlStepMeta.STATUS_APPROVED : HitlStepMeta.STATUS_DENIED;
                    StepEventBridge.emit(timelineBridgeId, session -> session.resolveHitlPending(hitlStatus));
                    timelineBridge.flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
                },
                () -> {
                    timelineBridge.progressBridgeToolStep(timelineBridgeId, HitlLabels.denied(),
                            generationMessageId, boundGenerationId, epoch);
                    StepEventBridge.emit(timelineBridgeId, session -> session.resolveHitlPending(HitlStepMeta.STATUS_DENIED));
                    timelineBridge.flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
                },
                () -> timelineBridge.progressBridgeToolStep(timelineBridgeId, HitlLabels.denied(),
                        generationMessageId, boundGenerationId, epoch));
    }

    public boolean awaitWorkflowConfirmation(
            WorkflowHitlScope.Binding workflow,
            String generationMessageId,
            String toolId,
            Map<String, String> params) {
        String displayName = toolCatalogService.displayName(toolId);
        String nodeStepId = workflow.nodeStepId();
        ProcessingTimelineSession session = workflow.session();
        String genMsgId = generationMessageId != null ? generationMessageId : workflow.generationMessageId();
        timelineBridge.emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.pending(displayName)), genMsgId);
        timelineBridge.emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.awaiting()), genMsgId);

        HitlTokenRegistry.HitlRegistration reg = tokenRegistry.register(genMsgId, toolId);
        String paramsSummary = HitlParamSupport.summarizeParams(params);
        HitlExpandPayload expand = resolveHitlExpand(genMsgId, toolId, params);
        timelineBridge.emitSessionStep(session, nodeStepId, s -> s.attachHitlPendingOnStep(
                nodeStepId, reg.token(), displayName, paramsSummary, reg.expiresAt(),
                expand.expandBody(), expand.editDiff()), genMsgId);
        timelineBridge.emitConfirmation(genMsgId, toolId, params, reg.token(), reg.expiresAt());

        return waitForDecision(reg, toolId, "HITL workflow", () -> {
        }, approved -> {
            if (approved) {
                timelineBridge.emitSessionStep(session, nodeStepId,
                        s -> s.progress(nodeStepId, HitlLabels.approved(displayName)), genMsgId);
            } else {
                timelineBridge.emitSessionStep(session, nodeStepId,
                        s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            }
            String hitlStatus = approved ? HitlStepMeta.STATUS_APPROVED : HitlStepMeta.STATUS_DENIED;
            timelineBridge.emitSessionStep(session, nodeStepId,
                    s -> s.resolveHitlPendingOnStep(nodeStepId, hitlStatus), genMsgId);
        }, () -> {
            timelineBridge.emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            timelineBridge.emitSessionStep(session, nodeStepId,
                    s -> s.resolveHitlPendingOnStep(nodeStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
        }, () -> timelineBridge.emitSessionStep(session, nodeStepId,
                s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId));
    }

    public boolean resumeReactAwaiting(String bridgeId, String assistantMsgId, ProcessingStep toolStep) {
        if (toolStep == null || toolStep.metadata() == null || toolStep.metadata().hitl() == null) {
            return false;
        }
        var hitl = toolStep.metadata().hitl();
        if (!HitlStepMeta.STATUS_AWAITING.equals(hitl.status())) {
            return false;
        }
        String toolStepId = toolStep.id();
        String toolId = ToolStepIds.catalogToolName(toolStepId);
        if (!StringUtils.hasText(toolId) || !StringUtils.hasText(assistantMsgId)) {
            return false;
        }
        Map<String, String> params = HitlParamSupport.parseParamsSummary(hitl.paramsSummary());
        String displayName = toolCatalogService.displayName(toolId);
        String genMsgId = assistantMsgId.strip();
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindTraceMessageId(genMsgId);
        long startedAt = System.currentTimeMillis();
        timelineBridge.emitSessionStep(session, toolStepId, s -> {
            s.startAt(toolStepId, "tool", startedAt);
            s.progress(toolStepId, HitlLabels.awaiting());
        }, genMsgId);

        HitlTokenRegistry.HitlRegistration reg = tokenRegistry.register(genMsgId, toolId);
        String paramsSummary = StringUtils.hasText(hitl.paramsSummary())
                ? hitl.paramsSummary() : HitlParamSupport.summarizeParams(params);
        timelineBridge.emitSessionStep(session, toolStepId, s -> s.attachHitlPendingOnStep(
                toolStepId, reg.token(), displayName, paramsSummary, reg.expiresAt()), genMsgId);
        timelineBridge.emitConfirmation(genMsgId, toolId, params, reg.token(), reg.expiresAt());

        return waitForDecision(reg, toolId, "HITL react resume", () -> {
        }, approved -> {
            if (approved) {
                timelineBridge.emitSessionStep(session, toolStepId,
                        s -> s.progress(toolStepId, HitlLabels.approved(displayName)), genMsgId);
                timelineBridge.emitSessionStep(session, toolStepId,
                        s -> s.resolveHitlPendingOnStep(toolStepId, HitlStepMeta.STATUS_APPROVED), genMsgId);
                StepEventBridge.grantHitlPreApproval(genMsgId, toolId, params);
            } else {
                timelineBridge.emitSessionStep(session, toolStepId,
                        s -> s.progress(toolStepId, HitlLabels.denied()), genMsgId);
                timelineBridge.emitSessionStep(session, toolStepId,
                        s -> s.resolveHitlPendingOnStep(toolStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
            }
        }, () -> {
            timelineBridge.emitSessionStep(session, toolStepId, s -> s.progress(toolStepId, HitlLabels.denied()), genMsgId);
            timelineBridge.emitSessionStep(session, toolStepId,
                    s -> s.resolveHitlPendingOnStep(toolStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
        }, () -> timelineBridge.emitSessionStep(session, toolStepId,
                s -> s.progress(toolStepId, HitlLabels.denied()), genMsgId));
    }

    public boolean resumeAwaitingFromCheckpoint(
            WorkflowHitlScope.Binding workflow,
            String generationMessageId,
            PendingInteraction pending,
            String toolId) {
        if (pending == null || !"hitl".equals(pending.kind())) {
            return false;
        }
        String resolvedToolId = StringUtils.hasText(toolId)
                ? toolId.strip()
                : (StringUtils.hasText(pending.hitlToolId()) ? pending.hitlToolId().strip() : "");
        if (!StringUtils.hasText(resolvedToolId)) {
            return false;
        }
        Map<String, String> params = HitlParamSupport.parseParamsSummary(pending.hitlParamsSummary());
        String displayName = toolCatalogService.displayName(resolvedToolId);
        String nodeStepId = workflow.nodeStepId();
        ProcessingTimelineSession session = workflow.session();
        String genMsgId = generationMessageId != null ? generationMessageId : workflow.generationMessageId();
        timelineBridge.emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.awaiting()), genMsgId);

        HitlTokenRegistry.HitlRegistration reg = tokenRegistry.register(genMsgId, resolvedToolId);
        String paramsSummary = StringUtils.hasText(pending.hitlParamsSummary())
                ? pending.hitlParamsSummary() : HitlParamSupport.summarizeParams(params);
        timelineBridge.emitSessionStep(session, nodeStepId, s -> s.attachHitlPendingOnStep(
                nodeStepId, reg.token(), displayName, paramsSummary, reg.expiresAt()), genMsgId);
        timelineBridge.emitConfirmation(genMsgId, resolvedToolId, params, reg.token(), reg.expiresAt());

        return waitForDecision(reg, resolvedToolId, "HITL resume", () -> {
        }, approved -> {
            if (approved) {
                timelineBridge.emitSessionStep(session, nodeStepId,
                        s -> s.progress(nodeStepId, HitlLabels.approved(displayName)), genMsgId);
            } else {
                timelineBridge.emitSessionStep(session, nodeStepId,
                        s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            }
            String hitlStatus = approved ? HitlStepMeta.STATUS_APPROVED : HitlStepMeta.STATUS_DENIED;
            timelineBridge.emitSessionStep(session, nodeStepId,
                    s -> s.resolveHitlPendingOnStep(nodeStepId, hitlStatus), genMsgId);
        }, () -> {
            timelineBridge.emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            timelineBridge.emitSessionStep(session, nodeStepId,
                    s -> s.resolveHitlPendingOnStep(nodeStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
        }, () -> timelineBridge.emitSessionStep(session, nodeStepId,
                s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId));
    }

    public boolean shouldConfirm(String toolId) {
        return shouldConfirmForBridge(toolId, StepEventBridge.resolveHitlBridgeId());
    }

    public boolean shouldConfirmForBridge(String toolId, String bridgeId) {
        if (!properties.isEnabled() || !StepEventBridge.hitlEnabledForBridge(bridgeId)) {
            return false;
        }
        return toolCatalogService.requiresConfirmation(toolId);
    }

    public boolean shouldConfirmWorkflow(String toolId, WorkflowHitlScope.Binding binding) {
        if (!properties.isEnabled() || binding == null) {
            return false;
        }
        return toolCatalogService.requiresConfirmation(toolId);
    }

    public String rejectionMessage() {
        return properties.getRejectionMessage();
    }

    public String skippedAfterSummary() {
        return HitlLabels.skippedAfter();
    }

    public void flushTimeline(String messageId) {
        timelineBridge.flushTimeline(messageId);
    }

    public void invalidateForMessageRestart(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        timelineBridge.invalidateForMessageRestart(messageId);
        tokenRegistry.cancelWaitersForMessage(messageId);
    }

    public void cancelWaitersForMessage(String messageId) {
        tokenRegistry.cancelWaitersForMessage(messageId);
    }

    public boolean confirm(String token, boolean approved) {
        return tokenRegistry.confirm(token, approved);
    }

    private record HitlExpandPayload(String expandBody, SandboxEditDiff editDiff) {}

    private HitlExpandPayload resolveHitlExpand(String bridgeId, String toolId, Map<String, String> params) {
        String expandBody = HitlParamSupport.expandBodyFromParams(params);
        if (!SandboxIds.EDIT.equals(toolId)) {
            return new HitlExpandPayload(expandBody, null);
        }
        SandboxEditDiff preview = null;
        try {
            String sid = Optional.ofNullable(SandboxSessionHolder.get(bridgeId))
                    .map(SandboxSessionHolder.Binding::sessionId)
                    .orElse(null);
            String path = params != null ? params.get("path") : null;
            if (sid != null && StringUtils.hasText(path)) {
                FsContentDto fs = sandboxClient.readFsContent(
                        sid, path, sandboxProperties.getWorkspaceContentMaxChars());
                String before = fs != null ? fs.content() : null;
                preview = EditDiffBuilder.tryBuild(
                                before,
                                params.get("old_string"),
                                params.get("new_string"),
                                3)
                        .map(d -> d.withPath(path))
                        .orElse(null);
            }
        } catch (Exception e) {
            log.debug("HITL editDiff preview skipped: {}", e.getMessage());
        }
        if (preview != null) {
            return new HitlExpandPayload(preview.toUnifiedText(), preview);
        }
        return new HitlExpandPayload(null, null);
    }

    private boolean waitForDecision(
            HitlTokenRegistry.HitlRegistration reg,
            String toolId,
            String logTag,
            Runnable beforeSuccess,
            Consumer<Boolean> onSuccess,
            Runnable onTimeout,
            Runnable onError) {
        try {
            boolean approved = reg.future().get(tokenRegistry.timeoutSec(), TimeUnit.SECONDS);
            beforeSuccess.run();
            log.info("[{}] token={} tool={} approved={}", logTag, reg.token(), toolId, approved);
            onSuccess.accept(approved);
            return approved;
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[{}] token={} tool={} 等待被中断（暂停/断连）", logTag, reg.token(), toolId);
            throw new HitlWaitInterruptedException();
        } catch (TimeoutException e) {
            log.warn("[{}] token={} tool={} 确认超时", logTag, reg.token(), toolId);
            onTimeout.run();
            return false;
        } catch (Exception e) {
            if (isWaitInterrupted(e)) {
                log.info("[{}] token={} tool={} 等待被中断（暂停/断连）", logTag, reg.token(), toolId);
                throw new HitlWaitInterruptedException();
            }
            log.warn("[{}] token={} tool={} 等待异常: {}", logTag, reg.token(), toolId, e.getMessage());
            onError.run();
            return false;
        } finally {
            tokenRegistry.cleanup(reg.token());
        }
    }

    private static boolean isWaitInterrupted(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HitlWaitInterruptedException || t instanceof java.util.concurrent.CancellationException) {
                return true;
            }
        }
        return false;
    }
}
