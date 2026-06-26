package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.execution.WorkflowPauseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class GenerationRegistry {

    private final WorkflowPauseService workflowPauseService;
    private final ConcurrentHashMap<String, GenerationJob> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageToGeneration = new ConcurrentHashMap<>();

    public GenerationJob register(GenerationJob job) {
        running.put(job.getGenerationId(), job);
        messageToGeneration.put(job.getMessageId(), job.getGenerationId());
        return job;
    }

    public Optional<GenerationJob> get(String generationId) {
        return Optional.ofNullable(running.get(generationId));
    }

    public Optional<GenerationJob> findByMessageId(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        String generationId = messageToGeneration.get(messageId);
        return generationId == null ? Optional.empty() : get(generationId);
    }

    public void remove(String generationId) {
        GenerationJob job = running.remove(generationId);
        if (job != null) {
            messageToGeneration.remove(job.getMessageId());
            unlockMessage(job.getMessageId());
        }
    }

    public void cancel(String generationId) {
        GenerationJob job = running.get(generationId);
        if (job != null) {
            workflowPauseService.requestPause(job.getMessageId());
            job.cancel();
            remove(generationId);
        }
    }

    /** 停止所有进行中的 generation（测试 teardown / 优雅停机） */
    public void cancelAll() {
        for (String generationId : java.util.List.copyOf(running.keySet())) {
            cancel(generationId);
        }
    }

    public boolean tryLockMessage(String messageId, String generationId) {
        return messageLocks.putIfAbsent(messageId, generationId) == null;
    }

    public void unlockMessage(String messageId) {
        messageLocks.remove(messageId);
    }
}
