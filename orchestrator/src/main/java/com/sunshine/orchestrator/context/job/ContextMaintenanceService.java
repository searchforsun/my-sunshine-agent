package com.sunshine.orchestrator.context.job;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文治理：L2 硬过期 void、superseded 清理、L3 向量 GC、L1 无主会话行删除。
 * 失败仅日志，不抛出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextMaintenanceService {

    private final UserContextStateRepository l2Repository;
    private final ConversationContextL1Repository l1Repository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final HistoryRagClient historyRagClient;
    private final ContextProperties contextProperties;

    public void runOnce() {
        try {
            Instant now = Instant.now();
            int voided = voidExpiredL2(now);
            int superseded = cleanupLongSuperseded(now);
            int vectors = gcL3Vectors();
            int orphanL1 = gcOrphanL1();
            log.info("[ContextMaintenance] done voided={} supersededDeleted={} vectorDeletes={} orphanL1={}",
                    voided, superseded, vectors, orphanL1);
        } catch (Exception e) {
            log.warn("[ContextMaintenance] runOnce failed: {}", e.getMessage());
        }
    }

    /** active + expires_at &lt; now → void，并删对应 L3 源向量。 */
    int voidExpiredL2(Instant now) {
        Instant clock = now != null ? now : Instant.now();
        List<UserContextStateEntity> expired =
                l2Repository.findByStatusAndExpiresAtBefore("active", clock);
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UserContextStateEntity entity : expired) {
            try {
                entity.setStatus("void");
                entity.setUpdatedAt(clock);
                l2Repository.save(entity);
                deleteVectorForSource(entity);
                count++;
            } catch (Exception e) {
                log.warn("[ContextMaintenance] void L2 failed id={}: {}",
                        entity.getId(), e.getMessage());
            }
        }
        return count;
    }

    /** 超过 retention 的 superseded 行物理删除。 */
    int cleanupLongSuperseded(Instant now) {
        Instant clock = now != null ? now : Instant.now();
        int days = contextProperties.getMaintenance().getSupersededRetentionDays();
        if (days <= 0) {
            return 0;
        }
        Instant cutoff = clock.minus(days, ChronoUnit.DAYS);
        List<UserContextStateEntity> stale =
                l2Repository.findByStatusAndUpdatedAtBefore("superseded", cutoff);
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UserContextStateEntity entity : stale) {
            try {
                l2Repository.delete(entity);
                count++;
            } catch (Exception e) {
                log.warn("[ContextMaintenance] delete superseded failed id={}: {}",
                        entity.getId(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * 删 void 源向量；以及 source_msg_id 对应 chat_message 已不存在的孤儿向量。
     */
    int gcL3Vectors() {
        Set<String> deletedKeys = new HashSet<>();
        int count = 0;
        List<UserContextStateEntity> voids = l2Repository.findByStatus("void");
        if (voids != null) {
            for (UserContextStateEntity entity : voids) {
                count += deleteVectorForSourceOnce(entity, deletedKeys);
            }
        }
        List<UserContextStateEntity> active = l2Repository.findByStatus("active");
        if (active != null) {
            for (UserContextStateEntity entity : active) {
                if (!StringUtils.hasText(entity.getSourceMsgId())) {
                    continue;
                }
                if (messageRepository.existsById(entity.getSourceMsgId())) {
                    continue;
                }
                count += deleteVectorForSourceOnce(entity, deletedKeys);
            }
        }
        return count;
    }

    /** 无对应 chat_conversation 的 L1 派生行。 */
    int gcOrphanL1() {
        List<ConversationContextL1Entity> rows = l1Repository.findAll();
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ConversationContextL1Entity row : rows) {
            try {
                String convId = row.getConvId();
                if (!StringUtils.hasText(convId) || conversationRepository.existsById(convId)) {
                    continue;
                }
                l1Repository.delete(row);
                count++;
            } catch (Exception e) {
                log.warn("[ContextMaintenance] delete orphan L1 failed conv={}: {}",
                        row.getConvId(), e.getMessage());
            }
        }
        return count;
    }

    private void deleteVectorForSource(UserContextStateEntity entity) {
        deleteVectorForSourceOnce(entity, null);
    }

    private int deleteVectorForSourceOnce(UserContextStateEntity entity, Set<String> deletedKeys) {
        if (entity == null || !StringUtils.hasText(entity.getSourceMsgId())) {
            return 0;
        }
        String userId = entity.getUserId();
        String tenantId = entity.getTenantId() != null ? entity.getTenantId() : "default";
        String msgId = entity.getSourceMsgId();
        String key = userId + "|" + tenantId + "|" + msgId;
        if (deletedKeys != null && !deletedKeys.add(key)) {
            return 0;
        }
        try {
            historyRagClient.delete(userId, tenantId, msgId).block();
            return 1;
        } catch (Exception e) {
            log.warn("[ContextMaintenance] L3 delete failed msg={}: {}", msgId, e.getMessage());
            return 0;
        }
    }
}
