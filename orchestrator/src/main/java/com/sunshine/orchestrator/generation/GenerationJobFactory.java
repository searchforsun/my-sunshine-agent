package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GenerationJobFactory {

    private final GenerationStreamService streamService;
    private final GenerationProperties properties;
    private final GenerationFlushScheduler flushScheduler;
    private final MemoryLifecycleService memoryLifecycleService;
    private final WorkflowPauseService workflowPauseService;
    private final ExecutionPlanStore executionPlanStore;
    private final AgentPauseProperties pauseProperties;
    private final DistributedGenerationLock flushLock;

    public GenerationJob create(String generationId, String messageId, String conversationId,
            String userId, String tenantId, String intent, String userQuery) {
        return new GenerationJob(
                generationId, messageId, conversationId, userId, tenantId, intent, userQuery,
                streamService, properties, flushScheduler, memoryLifecycleService,
                workflowPauseService, executionPlanStore, pauseProperties, flushLock);
    }
}
