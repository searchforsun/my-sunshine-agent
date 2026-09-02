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
 * 成功后 grant 预决策（供 checkpoint 重放 tool_call），并返回须注入 Prompt 的【用户决策】块
 * （停止路径常把 request_decision 记为终态 cancelled，模型不会再调工具）。
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
        List<DecisionQuestion> questions = meta.questions() != null ? meta.questions() : List.of();
        String title = meta.title() != null ? meta.title() : "";
        String fingerprint = DecisionFingerprint.of(title, questions);

        // 同进程残留预决策（上次 resume 已 resolve）：落 done + 注入，不二次阻塞
        var preGranted = StepEventBridge.peekDecisionPreApproval(msgId, fingerprint);
        if (preGranted.isPresent()) {
            DecisionResult existing = preGranted.get();
            if (StringUtils.hasText(bridge)) {
                String token = StringUtils.hasText(meta.token()) ? meta.token() : "preapproved";
                timelineSupport.rebindAwaiting(
                        bridge, decisionStep.id(), token, title, questions, meta.expiresAt());
                timelineSupport.complete(bridge, token, existing);
            }
            return DecisionResumeOutcome.resolved(List.of(buildResolvedInjectBlock(existing, questions)));
        }

        if (!StringUtils.hasText(bridge)) {
            log.warn("[DecisionResume] 无 bridge，中止续跑 re-await msg={}", msgId);
            return DecisionResumeOutcome.aborted();
        }

        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(msgId);
        String userId = audit != null && StringUtils.hasText(audit.userId()) ? audit.userId() : "";
        DecisionRegistry.Registration reg;
        try {
            reg = decisionRegistry.register(msgId, userId, title, questions);
        } catch (IllegalStateException e) {
            // D15 竞态仍有 awaiting：无法刷新 token，中止以免半吊子续跑（对齐 HITL interrupt）
            log.warn("[DecisionResume] register 失败，中止续跑 msg={}: {}", msgId, e.getMessage());
            return DecisionResumeOutcome.aborted();
        }

        timelineSupport.rebindAwaiting(
                bridge, decisionStep.id(), reg.token(), title, questions, reg.expiresAt());
        try {
            DecisionResult result = decisionRegistry.awaitDecision(reg);
            if ("timeout".equals(result.outcome())) {
                timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterTimeout());
                return DecisionResumeOutcome.aborted();
            }
            if ("cancelled".equals(result.outcome())) {
                timelineSupport.pause(bridge, reg.token(), DecisionLabels.afterCancel());
                return DecisionResumeOutcome.aborted();
            }
            timelineSupport.complete(bridge, reg.token(), result);
            StepEventBridge.grantDecisionPreApproval(msgId, fingerprint, result);
            return DecisionResumeOutcome.resolved(List.of(buildResolvedInjectBlock(result, questions)));
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

    /** 续跑注入块：复用 Tool 短格式（outcome/title/choice 或 outcome=skipped）。 */
    static String buildResolvedInjectBlock(DecisionResult result, List<DecisionQuestion> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户决策】");
        String title = result != null && result.title() != null ? result.title() : "";
        if (StringUtils.hasText(title)) {
            sb.append('\n').append(title.strip());
        }
        if (result != null && "skipped".equals(result.outcome())) {
            sb.append('\n').append(RequestDecisionTool.formatSkippedResult());
            return sb.toString();
        }
        List<DecisionAnswer> answers = result != null && result.answers() != null
                ? result.answers()
                : List.of();
        sb.append('\n').append(RequestDecisionTool.formatSuccessResult(title, questions, answers));
        return sb.toString();
    }
}
