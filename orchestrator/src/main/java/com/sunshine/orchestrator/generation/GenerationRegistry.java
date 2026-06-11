package com.sunshine.orchestrator.generation;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GenerationRegistry {

    private final ConcurrentHashMap<String, GenerationJob> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageLocks = new ConcurrentHashMap<>();

    public GenerationJob register(GenerationJob job) {
        running.put(job.getGenerationId(), job);
        return job;
    }

    public Optional<GenerationJob> get(String generationId) {
        return Optional.ofNullable(running.get(generationId));
    }

    public void remove(String generationId) {
        GenerationJob job = running.remove(generationId);
        if (job != null) {
            unlockMessage(job.getMessageId());
        }
    }

    public void cancel(String generationId) {
        GenerationJob job = running.get(generationId);
        if (job != null) {
            job.cancel();
            remove(generationId);
        }
    }

    public boolean tryLockMessage(String messageId, String generationId) {
        return messageLocks.putIfAbsent(messageId, generationId) == null;
    }

    public void unlockMessage(String messageId) {
        messageLocks.remove(messageId);
    }
}
