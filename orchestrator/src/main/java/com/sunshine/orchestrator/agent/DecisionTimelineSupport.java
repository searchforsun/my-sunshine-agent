package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.DecisionLabels;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** ReAct 主时间线 decision 卡：等待 / 完成 / 暂停 / 失败 */
@Component
public class DecisionTimelineSupport {

    /** begin/rebind 载荷：enqueueAuxiliary 不进 session.snapshot，终态需本地保留 title/questions */
    private final ConcurrentHashMap<String, DecisionStepMeta> pendingByToken = new ConcurrentHashMap<>();
    /** 续跑 rebind 时保留原 stepId，避免 decision-{newToken} 另开一张卡 */
    private final ConcurrentHashMap<String, String> stepIdByToken = new ConcurrentHashMap<>();

    public void begin(
            String bridgeId,
            String token,
            String title,
            List<DecisionQuestion> questions,
            Long expiresAt) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(token)) {
            return;
        }
        DecisionStepMeta decision = new DecisionStepMeta(
                token, title, questions, expiresAt, null, null);
        pendingByToken.put(token, decision);
        String stepId = "decision-" + token;
        stepIdByToken.put(token, stepId);
        StepEventBridge.emit(bridgeId, session -> {
            long ts = System.currentTimeMillis();
            String label = StringUtils.hasText(title) ? title : DecisionLabels.label();
            StepMetadata metadata = StepMetadata.withDecision(null, decision);
            StepSummary summary = new StepSummary(
                    DecisionLabels.before(),
                    DecisionLabels.active(title),
                    null);
            ProcessingStep card = new ProcessingStep(
                    stepId,
                    "decision",
                    "awaiting",
                    summary,
                    ts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ts,
                    label,
                    metadata,
                    null,
                    null,
                    null);
            session.enqueueAuxiliary(StreamToken.step(card));
        });
    }

    /**
     * 续跑：同一 stepId 更新 token/expiresAt，不改 title/questions。
     */
    public void rebindAwaiting(
            String bridgeId,
            String stepId,
            String token,
            String title,
            List<DecisionQuestion> questions,
            Long expiresAt) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(stepId) || !StringUtils.hasText(token)) {
            return;
        }
        DecisionStepMeta decision = new DecisionStepMeta(
                token, title, questions, expiresAt, null, null);
        pendingByToken.put(token, decision);
        stepIdByToken.put(token, stepId);
        StepEventBridge.emit(bridgeId, session -> {
            long ts = System.currentTimeMillis();
            String label = StringUtils.hasText(title) ? title : DecisionLabels.label();
            StepSummary summary = new StepSummary(
                    DecisionLabels.before(),
                    DecisionLabels.active(title),
                    null);
            ProcessingStep card = new ProcessingStep(
                    stepId,
                    "decision",
                    "awaiting",
                    summary,
                    ts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ts,
                    label,
                    StepMetadata.withDecision(null, decision),
                    null,
                    null,
                    null);
            session.enqueueAuxiliary(StreamToken.step(card));
        });
    }

    public void complete(String bridgeId, String token, DecisionResult result) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(token) || result == null) {
            return;
        }
        DecisionStepMeta prior = pendingByToken.remove(token);
        DecisionStepMeta updated = withOutcome(prior, token, result);
        String stepId = stepIdByToken.remove(token);
        if (!StringUtils.hasText(stepId)) {
            stepId = "decision-" + token;
        }
        String finalStepId = stepId;
        StepEventBridge.emit(bridgeId, session -> {
            long ts = System.currentTimeMillis();
            List<DecisionQuestion> questions = updated.questions();
            String afterText;
            if ("skipped".equals(result.outcome())) {
                afterText = DecisionLabels.afterSkip();
            } else {
                String choiceText = DecisionLabels.formatChoiceFromAnswers(questions, result.answers());
                afterText = DecisionLabels.after(choiceText);
            }
            StepSummary summary = new StepSummary(
                    DecisionLabels.before(),
                    null,
                    afterText);
            ProcessingStep card = new ProcessingStep(
                    finalStepId,
                    "decision",
                    "done",
                    summary,
                    null,
                    ts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ts,
                    priorLabel(prior, result.title()),
                    StepMetadata.withDecision(null, updated),
                    null,
                    null,
                    null);
            session.enqueueAuxiliary(StreamToken.step(card));
        });
    }

    public void pause(String bridgeId, String token, String afterText) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(token)) {
            return;
        }
        DecisionStepMeta prior = pendingByToken.remove(token);
        String stepId = stepIdByToken.remove(token);
        if (!StringUtils.hasText(stepId)) {
            stepId = "decision-" + token;
        }
        String finalStepId = stepId;
        StepEventBridge.emit(bridgeId, session -> {
            long ts = System.currentTimeMillis();
            String after = StringUtils.hasText(afterText) ? afterText.strip() : DecisionLabels.afterCancel();
            StepSummary summary = new StepSummary(DecisionLabels.before(), null, after);
            ProcessingStep card = new ProcessingStep(
                    finalStepId,
                    "decision",
                    "paused",
                    summary,
                    null,
                    ts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ts,
                    priorLabel(prior, null),
                    prior != null ? StepMetadata.withDecision(null, prior) : null,
                    null,
                    null,
                    null);
            session.enqueueAuxiliary(StreamToken.step(card));
        });
    }

    public void fail(String bridgeId, String token, String errorMsg) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(token)) {
            return;
        }
        DecisionStepMeta prior = pendingByToken.remove(token);
        String stepId = stepIdByToken.remove(token);
        if (!StringUtils.hasText(stepId)) {
            stepId = "decision-" + token;
        }
        String finalStepId = stepId;
        StepEventBridge.emit(bridgeId, session -> {
            long ts = System.currentTimeMillis();
            String after = StringUtils.hasText(errorMsg) ? errorMsg.strip() : DecisionLabels.afterFail();
            StepSummary summary = new StepSummary(DecisionLabels.before(), null, after);
            ProcessingStep card = new ProcessingStep(
                    finalStepId,
                    "decision",
                    "error",
                    summary,
                    null,
                    ts,
                    null,
                    after,
                    null,
                    null,
                    after,
                    ts,
                    priorLabel(prior, null),
                    prior != null ? StepMetadata.withDecision(null, prior) : null,
                    null,
                    null,
                    null);
            session.enqueueAuxiliary(StreamToken.step(card));
        });
    }

    private static DecisionStepMeta withOutcome(DecisionStepMeta prior, String token, DecisionResult result) {
        if (prior == null) {
            return new DecisionStepMeta(
                    token, result.title(), null, null, result.outcome(), result.answers());
        }
        return new DecisionStepMeta(
                prior.token(),
                prior.title(),
                prior.questions(),
                prior.expiresAt(),
                result.outcome(),
                result.answers());
    }

    private static String priorLabel(DecisionStepMeta prior, String fallback) {
        if (prior != null && StringUtils.hasText(prior.title())) {
            return prior.title();
        }
        return StringUtils.hasText(fallback) ? fallback.strip() : DecisionLabels.label();
    }
}
