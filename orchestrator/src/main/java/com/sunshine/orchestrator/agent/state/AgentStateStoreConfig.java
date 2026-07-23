package com.sunshine.orchestrator.agent.state;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * AgentState Redis Store（spec §4.1：Redis-only · TTL 7d · key 前缀隔离）。
 * 2.0 SDK 真实签名：{@code RedisAgentStateStore.builder().lettuceClient(RedisClient).keyPrefix(...).build()}
 * —— 无 TTL 参数、无 Spring StringRedisTemplate 构造器；TTL 由 P2 在 save 侧（或后续扩展）落地。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentStateStoreConfig {

    private final RedisProperties redisProperties;
    private final AgentExecutionProperties props;

    @Bean(destroyMethod = "shutdown")
    public RedisClient agentStateRedisClient() {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redisProperties.getHost())
                .withPort(redisProperties.getPort())
                .withDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getPassword())) {
            uri.withPassword(redisProperties.getPassword().toCharArray());
        }
        Duration timeout = redisProperties.getTimeout();
        if (timeout != null) {
            uri.withTimeout(timeout);
        }
        return RedisClient.create(uri.build());
    }

    @Bean
    public AgentStateStore redisAgentStateStore(RedisClient agentStateRedisClient) {
        AgentExecutionProperties.As2 as2 = props.getAs2();
        log.info("[AgentStateStoreConfig] init RedisAgentStateStore prefix={} ttlSec={} (ttl applied at P2)",
                as2.getStateKeyPrefix(), as2.getStateTtlSec());
        return RedisAgentStateStore.builder()
                .lettuceClient(agentStateRedisClient)
                .keyPrefix(as2.getStateKeyPrefix())
                .build();
    }
}
