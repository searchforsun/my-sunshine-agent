package com.sunshine.orchestrator.agent.state;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.lettuce.core.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * AgentState checkpoint 的 TTL 落地（spec §4.1 锁定 7 天）。
 * <p>SDK {@code RedisAgentStateStore.save()} 只 SET 不设过期（RedisAgentStateStore.java save 方法
 * 无 TTL 参数），{@code agent.as2.state-ttl-sec} 若无此支撑类即为死配置；缺 TTL 时 checkpoint
 * key 永不过期，Redis 内存淘汰会误伤活跃会话的断点数据。
 * <p>key 布局须与 SDK 构造规则一致：单值 {@code {prefix}{userId}/{sessionId}:{key}}、
 * 列表 {@code {prefix}{userId}/{sessionId}:{key}:list}（附加 {@code :_hash}）、
 * 键集 {@code {prefix}{userId}/{sessionId}:_keys}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStateTtlSupport {

    private static final String LIST_SUFFIX = ":list";
    private static final String LIST_HASH_SUFFIX = ":list:_hash";
    private static final String KEYS_SUFFIX = ":_keys";

    private final RedisClient agentStateRedisClient;
    private final AgentExecutionProperties props;
    private final AgentStateStore agentStateStore;

    /** checkpoint 保存后调用：对该 slot 的全部 key 落 TTL；store 非 Redis 实现或 TTL≤0 时跳过 */
    public void applyTtlAfterSave(String userId, String sessionId) {
        if (!(agentStateStore instanceof RedisAgentStateStore) || !(agentStateRedisClient != null)) {
            return;
        }
        long ttlSec = props.getAs2().getStateTtlSec();
        if (ttlSec <= 0 || !StringUtils.hasText(sessionId)) {
            return;
        }
        String prefix = props.getAs2().getStateKeyPrefix();
        String slot = (userId == null || userId.isBlank() ? "__anon__" : userId) + "/" + sessionId;
        String keysKey = prefix + slot + KEYS_SUFFIX;
        try {
            var sync = agentStateRedisClient.connect().sync();
            List<String> trackedKeys = sync.smembers(keysKey).stream().toList();
            java.util.Set<String> redisKeys = new java.util.HashSet<>();
            redisKeys.add(keysKey);
            for (String tracked : trackedKeys) {
                String baseKey = prefix + slot + ":" + tracked;
                if (tracked.endsWith(LIST_SUFFIX)) {
                    redisKeys.add(baseKey);
                    redisKeys.add(baseKey + LIST_HASH_SUFFIX);
                } else {
                    redisKeys.add(baseKey);
                }
            }
            redisKeys.forEach(key -> sync.expire(key, Duration.ofSeconds(ttlSec)));
        } catch (Exception e) {
            // TTL 失败不影响主链路：checkpoint 已保存成功，仅生命周期兜底缺失
            log.warn("[AgentStateTtl] apply ttl failed user={} session={}: {}",
                    userId, sessionId, e.getMessage());
        }
    }
}
