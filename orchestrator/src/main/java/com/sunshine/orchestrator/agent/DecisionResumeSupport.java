package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.execution.ReactResumeContextSupport;
import com.sunshine.orchestrator.processing.DecisionLabels;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ReAct MAIN 续跑：对 awaiting/paused decision 卡 re-register token 并阻塞 await；
 * 成功后 grant 预决策（供 checkpoint 重放 tool_call），并返回须注入 Prompt 的【用户决策】块
 * （停止路径常把 request_decision 记为终态 {@code __cancelled__}，模型不会再调工具）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionResumeSupport {

    private final DecisionRegistry decisionRegistry;
    private final DecisionTimelineSupport timelineSupport;

    /**
     * @return {@link DecisionResumeOutcome#resolved} 含注入块；timeout/cancel/注册失败 → {@link DecisionResumeOutcome#abort}
     */
    public DecisionResumeOutcome prepareOnReactResume(
            String messageId, String bridgeId, List<ProcessingStep> steps) {
        ProcessingStep decisionStep = ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(steps);
        if (decisionStep == null || decisionStep.metadata() == null || decisionStep.metadata().decision() == null) {
            return DecisionResumeOutcome.none();
        }
        if (!StringUtils.hasText(messageId)) {
            return DecisionResumeOutcome.none();
        }
        String msgId = messageId.strip();
        String bridge = StringUtils.hasText(bridgeId) ? bridgeId.strip() : StepEventBridge.activeMainBridge(msgId);
        DecisionStepMeta meta = decisionStep.metadata().decision();
        List<DecisionOption> options = meta.options() != null ? meta.options() : List.of();
        String question = meta.question() != null ? meta.question() : "";
        String fingerprint = DecisionFingerprint.of(question, options);

        // 同进程残留预决策（上次 resume 已 resolve）：落 done + 注入，不二次阻塞
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
            return DecisionResumeOutcome.resolved(List.of(
                    ReactResumeContextSupport.buildResolvedDecisionBlock(
                            question, existing.choice(), label, existing.customInput())));
        }

        if (!StringUtils.hasText(bridge)) {
            log.warn("[DecisionResume] 无 bridge，中止续跑 re-await msg={}", msgId);
            return DecisionResumeOutcome.aborted();
        }

        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(msgId);
        String userId = audit != null && StringUtils.hasText(audit.userId()) ? audit.userId() : "";
        DecisionRegistry.Registration reg;
        try {
            reg = decisionRegistry.register(
                    msgId, userId, question, options, meta.allowCustomInput());
        } catch (IllegalStateException e) {
            // D15 竞态仍有 awaiting：无法刷新 token，中止以免半吊子续跑（对齐 HITL interrupt）
            log.warn("[DecisionResume] register 失败，中止续跑 msg={}: {}", msgId, e.getMessage());
            return DecisionResumeOutcome.aborted();
        }

        timelineSupport.refreshAwaiting(
                bridge, decisionStep.id(), reg.token(), question, options,
                meta.allowCustomInput(), reg.expiresAt());
        try {
            DecisionResult result = decisionRegistry.awaitDecision(reg);
            if ("__timeout__".equals(result.choice())) {
                timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterTimeout());
                // 对齐 HITL：等待中断 → GenerationJob INTERRUPTED，不继续 streamEvents
                return DecisionResumeOutcome.aborted();
            }
            if ("__cancelled__".equals(result.choice())) {
                timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterCancel());
                return DecisionResumeOutcome.aborted();
            }
            String label = resolveLabel(options, result.choice());
            timelineSupport.complete(bridge, reg.token(), result, label);
            StepEventBridge.grantDecisionPreApproval(msgId, fingerprint, result);
            return DecisionResumeOutcome.resolved(List.of(
                    ReactResumeContextSupport.buildResolvedDecisionBlock(
                            question, result.choice(), label, result.customInput())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterCancel());
            return DecisionResumeOutcome.aborted();
        } catch (RuntimeException e) {
            String err = StringUtils.hasText(e.getMessage()) ? e.getMessage().strip() : "决策续跑失败";
            log.warn("[DecisionResume] await 失败 msg={}: {}", msgId, err);
            timelineSupport.fail(bridge, reg.token(), err);
            return DecisionResumeOutcome.aborted();
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
