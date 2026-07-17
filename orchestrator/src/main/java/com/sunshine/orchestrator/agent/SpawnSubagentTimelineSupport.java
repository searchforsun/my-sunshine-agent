package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** ReAct 主时间线 subagent 卡：创建 / 折叠 / 完成 */
@Component
public class SpawnSubagentTimelineSupport {

    public void begin(String mainBridgeId, String runId, String displayLabel, String spawnPrompt) {
        if (!StringUtils.hasText(mainBridgeId) || !StringUtils.hasText(runId)) {
            return;
        }
        StepEventBridge.emit(mainBridgeId, session -> {
            long ts = System.currentTimeMillis();
            String stepId = SpawnSubagentTimelineBridge.parentStepId(runId);
            String label = StringUtils.hasText(displayLabel) ? displayLabel.strip() : SpawnSubagentLabels.label();
            StepMetadata metadata = StepMetadata.withSpawnPrompt(null, spawnPrompt);
            StepSummary summary = new StepSummary(
                    SpawnSubagentLabels.before(),
                    SpawnSubagentLabels.active(label),
                    null);
            ProcessingStep card = new ProcessingStep(
                    stepId,
                    "subagent",
                    "running",
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
                    null);
            session.enqueueAuxiliary(StreamToken.step(card));
        });
    }

    public void fold(String mainBridgeId, SpawnSubagentTimelineBridge bridge, StreamToken token) {
        if (!StringUtils.hasText(mainBridgeId) || bridge == null || token == null) {
            return;
        }
        StepEventBridge.emit(mainBridgeId, session -> {
            for (StreamToken wrapped : bridge.wrap(token)) {
                session.enqueueAuxiliary(wrapped);
            }
        });
    }

    public void complete(String mainBridgeId, SpawnSubagentTimelineBridge bridge, String result) {
        if (!StringUtils.hasText(mainBridgeId) || bridge == null) {
            return;
        }
        StepEventBridge.emit(mainBridgeId, session -> {
            for (StreamToken token : bridge.complete(SpawnSubagentLabels.after(), result, true)) {
                session.enqueueAuxiliary(token);
            }
        });
    }

    public void fail(String mainBridgeId, SpawnSubagentTimelineBridge bridge, String result) {
        if (!StringUtils.hasText(mainBridgeId) || bridge == null) {
            return;
        }
        StepEventBridge.emit(mainBridgeId, session -> {
            for (StreamToken token : bridge.complete(SpawnSubagentLabels.afterFail(), result, false)) {
                session.enqueueAuxiliary(token);
            }
        });
    }
}
