package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionLabels;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ReAct MAIN 续跑：对 awaiting/paused decision 卡 re-register token 并阻塞 await；
 * 成功后 grant 预决策，供 checkpoint 重放 request_decision 时跳过二次出题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionResumeSupport {

    private final DecisionRegistry decisionRegistry;
    private final DecisionTimelineSupport timelineSupport;

    /**
     * 续跑前准备：无待决策则直接返回；已有 choice / 预决策则落 done；
     * 否则重发 token、更新 metadata（不改 question/options）并 await。
     */
    public void prepareOnReactResume(String messageId, String bridgeId, List<ProcessingStep> steps) {
        ProcessingStep decisionStep = ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(steps);
        if (decisionStep == null || decisionStep.metadata() == null || decisionStep.metadata().decision() == null) {
            return;
        }
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        String msgId = messageId.strip();
        String bridge = StringUtils.hasText(bridgeId) ? bridgeId.strip() : StepEventBridge.activeMainBridge(msgId);
        DecisionStepMeta meta = decisionStep.metadata().decision();
        List<DecisionOption> options = meta.options() != null ? meta.options() : List.of();
        String question = meta.question() != null ? meta.question() : "";
        String fingerprint = DecisionFingerprint.of(question, options);

        if (StringUtils.hasText(meta.choice())
                && !"__timeout__".equals(meta.choice())
                && !"__cancelled__".equals(meta.choice())) {
            DecisionResult existing = new DecisionResult(
                    meta.choice(), meta.customInput(), System.currentTimeMillis());
            String label = resolveLabel(options, existing.choice());
            if (StringUtils.hasText(bridge)) {
                timelineSupport.refreshAwaiting(
                        bridge, decisionStep.id(), meta.token(), question, options,
                        meta.allowCustomInput(), meta.expiresAt());
                timelineSupport.complete(bridge, meta.token(), existing, label);
            }
            StepEventBridge.grantDecisionPreApproval(msgId, fingerprint, existing);
            return;
        }

        var preGranted = StepEventBridge.peekDecisionPreApproval(msgId, fingerprint);
        if (preGranted.isPresent()) {
            DecisionResult existing = preGranted.get();
            String label = resolveLabel(options, existing.choice());
            if (StringUtils.hasText(bridge)) {
                String token = StringUtils.hasText(meta.token()) ? meta.token() : "preapproved";
                timelineSupport.refreshAwaiting(
                        bridge, decisionStep.id(), token, question, options,
                        meta.allowCustomInput(), meta.expiresAt());
                timelineSupport.complete(bridge, token, existing, label);
            }
            return;
        }

        if (!StringUtils.hasText(bridge)) {
            log.warn("[DecisionResume] 无 bridge，跳过 re-await msg={}", msgId);
            return;
        }

        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(msgId);
        String userId = audit != null && StringUtils.hasText(audit.userId()) ? audit.userId() : "";
        DecisionRegistry.Registration reg;
        try {
            reg = decisionRegistry.register(
                    msgId, userId, question, options, meta.allowCustomInput());
        } catch (IllegalStateException e) {
            log.warn("[DecisionResume] register 失败 msg={}: {}", msgId, e.getMessage());
            return;
        }

        timelineSupport.refreshAwaiting(
                bridge, decisionStep.id(), reg.token(), question, options,
                meta.allowCustomInput(), reg.expiresAt());
        try {
            DecisionResult result = decisionRegistry.awaitDecision(reg);
            if ("__timeout__".equals(result.choice())) {
                timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterTimeout());
                return;
            }
            if ("__cancelled__".equals(result.choice())) {
                timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterCancel());
                return;
            }
            String label = resolveLabel(options, result.choice());
            timelineSupport.complete(bridge, reg.token(), result, label);
            StepEventBridge.grantDecisionPreApproval(msgId, fingerprint, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterCancel());
        } catch (RuntimeException e) {
            String err = StringUtils.hasText(e.getMessage()) ? e.getMessage().strip() : "决策续跑失败";
            log.warn("[DecisionResume] await 失败 msg={}: {}", msgId, err);
            timelineSupport.fail(bridge, reg.token(), err);
        }
    }

    private static String resolveLabel(List<DecisionOption> options, String choice) {
        if (!StringUtils.hasText(choice) || options == null) {
            return "";
        }
        return options.stream()
                .filter(o -> choice.equals(o.value()))
                .map(DecisionOption::label)
                .findFirst()
                .orElse("");
    }
}
