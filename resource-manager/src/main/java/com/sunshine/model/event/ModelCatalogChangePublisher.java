package com.sunshine.model.event;

import com.sunshine.common.model.ModelCatalogChannels;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 模型 Catalog 变更 Redis 广播 */
@Component
@RequiredArgsConstructor
public class ModelCatalogChangePublisher {

    private final StringRedisTemplate redis;

    /**
     * 事务提交后才广播：若在事务内提前发出，消费方（llm-gateway）会立即回读 catalog，
     * 在 REPEATABLE READ 下读不到未提交数据，导致热更新用旧快照覆盖、配置变更「看似不生效」。
     * 无事务时（防御）保持同步发送语义。
     */
    public void publish(String tenantId) {
        String payload = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redis.convertAndSend(ModelCatalogChannels.CHANGED, payload);
                }
            });
        } else {
            redis.convertAndSend(ModelCatalogChannels.CHANGED, payload);
        }
    }
}
