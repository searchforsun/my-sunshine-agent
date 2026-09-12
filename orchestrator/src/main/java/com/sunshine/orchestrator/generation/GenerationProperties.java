package com.sunshine.orchestrator.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.generation")
public record GenerationProperties(
        long ttlSec,
        long orphanTimeoutSec,
        int maxBufferChunks,
        long reconnectBlockMs,
        long flushIntervalMs,
        int maxChunkChars,
        long maxStreamLen,
        /** SSE 心跳间隔：长异步等待（spawn/worker await）期间连接静默会触发上游读空闲超时，须周期发注释行保活 */
        Long heartbeatIntervalMs
) {
    /** 心跳间隔生效值：配置缺失时回退 15s，须小于 Gateway 最短读空闲档（360s） */
    public long heartbeatInterval() {
        return heartbeatIntervalMs != null && heartbeatIntervalMs > 0 ? heartbeatIntervalMs : 15_000L;
    }
}
