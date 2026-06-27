package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.config.GenerationLockProperties;
import com.sunshine.testsupport.EmbeddedRedisTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmbeddedRedisTestConfig.class)
@EnableConfigurationProperties(GenerationProperties.class)
class DistributedGenerationLockTest {

    @Autowired
    private StringRedisTemplate redis;

    private DistributedGenerationLock lockA;
    private DistributedGenerationLock lockB;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", EmbeddedRedisTestConfig::redisHost);
        registry.add("spring.data.redis.port", EmbeddedRedisTestConfig::redisPort);
        registry.add("spring.data.redis.password", () -> "");
    }

    @BeforeEach
    void setUp() {
        Set<String> keys = redis.keys("sunshine:generation:lock:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        GenerationLockProperties props = new GenerationLockProperties();
        props.setEnabled(true);
        props.setTtlSec(30L);
        lockA = new DistributedGenerationLock(redis, props);
        lockA.initOwnerId();
        lockB = new DistributedGenerationLock(redis, props);
        lockB.initOwnerId();
    }

    @Test
    void onlyOneInstanceAcquiresSameGeneration() {
        assertThat(lockA.tryAcquire("gen-1")).isTrue();
        assertThat(lockB.tryAcquire("gen-1")).isFalse();
        assertThat(lockA.isHeldByThisInstance("gen-1")).isTrue();
        assertThat(lockB.isHeldByThisInstance("gen-1")).isFalse();
    }

    @Test
    void releaseAllowsAnotherInstanceToAcquire() {
        assertThat(lockA.tryAcquire("gen-2")).isTrue();
        lockA.release("gen-2");
        assertThat(lockB.tryAcquire("gen-2")).isTrue();
    }

    @Test
    void renewIfHeldExtendsOwnership() {
        assertThat(lockA.tryAcquire("gen-3")).isTrue();
        assertThat(lockA.renewIfHeld("gen-3")).isTrue();
        assertThat(lockB.renewIfHeld("gen-3")).isFalse();
    }

    @Test
    void disabledLockAlwaysSucceeds() {
        GenerationLockProperties off = new GenerationLockProperties();
        off.setEnabled(false);
        DistributedGenerationLock lock = new DistributedGenerationLock(redis, off);
        lock.initOwnerId();
        assertThat(lock.tryAcquire("gen-off")).isTrue();
        assertThat(lock.tryAcquire("gen-off")).isTrue();
    }
}
