package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 多实例 GenerationJob flush 分布式锁（Task 3.14） */
@Data
@ConfigurationProperties(prefix = "agent.generation.lock")
public class GenerationLockProperties {

    /** 生产多实例建议 true；单实例 dev 可关 */
    private boolean enabled = true;
    /** Redis key TTL（秒），持锁实例 flush 时续期 */
    private long ttlSec = 30L;
    private String keyPrefix = "sunshine:generation:lock:";
}
