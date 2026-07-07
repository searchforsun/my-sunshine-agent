package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.execution.WorkflowContextCodec;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PendingInteraction;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/** Workflow 暂停检查点构建与 wfCtx 解析 */
@Slf4j
final class GenerationJobCheckpointSupport {

    private GenerationJobCheckpointSupport() {
    }

    static WorkflowCheckpoint buildPauseCheckpoint(
            String messageId,
            List<ProcessingStep> stepsBuffer,
            WorkflowPauseService workflowPauseService,
            ExecutionPlanStore executionPlanStore,
            AgentPauseProperties pauseProperties,
            com.sunshine.orchestrator.plan.ExecutionPlanEntity entity) {
        PendingInteraction pending = pauseProperties.isResumeInteractionEnabled()
                ? ProcessingStepLifecycleOps.findPendingInteraction(stepsBuffer) : null;
        if (pending != null) {
            String ctxJson = resolveWfCtxJson(messageId, pending.nodeId(), workflowPauseService, executionPlanStore);
            return new WorkflowCheckpoint(pending.nodeId(), ctxJson, PausePhase.EXECUTING, pending);
        }
        String nodeId = workflowPauseService.getCurrentNodeId(messageId);
        if (!StringUtils.hasText(nodeId)) {
            nodeId = ProcessingStepLifecycleOps.findLastRunningWorkflowNodeId(stepsBuffer);
        }
        if (StringUtils.hasText(nodeId)) {
            return new WorkflowCheckpoint(nodeId, resolveWfCtxJson(messageId, nodeId, workflowPauseService, executionPlanStore),
                    PausePhase.EXECUTING, null);
        }
        String resumeNodeId = executionPlanStore.inferPlanningResumeNodeId(entity);
        return new WorkflowCheckpoint(resumeNodeId, "{}", PausePhase.PLANNING, null);
    }

    static String resolveWfCtxJson(
            String messageId,
            String nodeId,
            WorkflowPauseService workflowPauseService,
            ExecutionPlanStore executionPlanStore) {
        String ctxJson = workflowPauseService.getCommittedContextJson(messageId);
        if (!WorkflowContextCodec.hasNodes(ctxJson)) {
            ctxJson = executionPlanStore.findByMessageId(messageId)
                    .filter(e -> StringUtils.hasText(e.getPauseCheckpoint()))
                    .map(executionPlanStore::loadCheckpoint)
                    .filter(cp -> WorkflowContextCodec.hasNodes(cp.wfCtxJson()))
                    .map(WorkflowCheckpoint::wfCtxJson)
                    .orElse(ctxJson);
        }
        if (!WorkflowContextCodec.hasNodes(ctxJson)) {
            log.warn("[GenerationJob] 暂停检查点 wfCtx 为空 msg={} node={}，续跑可能丢失上游",
                    messageId, nodeId);
        }
        return ctxJson;
    }
}
