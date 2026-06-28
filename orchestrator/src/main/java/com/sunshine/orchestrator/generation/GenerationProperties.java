package com.sunshine.orchestrator.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.generation")
public record GenerationProperties(
        long ttlSec,
        long orphanTimeoutSec,
        int maxBufferChunks,
        long reconnectBlockMs,
        long flushIntervalMs,
        int maxChunkChars
) {
}
