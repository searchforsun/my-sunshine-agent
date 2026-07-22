package com.sunshine.orchestrator.context.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时上下文治理：过期 L2 void、superseded 清理、L3 孤儿向量 GC、L1 无主行。
 * L2 void 不删 chat-history 向量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextMaintenanceJob {

    private final ContextMaintenanceService service;

    @Scheduled(fixedDelayString = "${agent.context.maintenance.interval-ms:3600000}")
    public void tick() {
        service.runOnce();
    }
}
