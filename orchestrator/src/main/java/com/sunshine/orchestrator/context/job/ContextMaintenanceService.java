package com.sunshine.orchestrator.context.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文治理：L2 硬过期 void、superseded 清理、L3 孤儿向量 GC、L1 无主会话行删除。
 * L2 生命周期与 L3 chat-history 向量解耦：L2 void/过期不删对话向量。
 * 失败仅日志，不抛出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextMaintenanceService {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

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

    /** active + expires_at &lt; now → void（仅改 L2 状态，不触碰 L3 向量）。 */
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
     * 仅删真孤儿 L3 向量：chat_message 已不存在的 msgId；
     * 以及会话已删除时 L1 上残留的 far_folded / mid 键。
     * 禁止因 L2 void/过期删除（source_msg_id 只是抽取溯源）。
     */
    int gcL3Vectors() {
        Set<String> deletedKeys = new HashSet<>();
        int count = 0;
        List<UserContextStateEntity> l2Rows = l2Repository.findAll();
        if (l2Rows != null) {
            for (UserContextStateEntity entity : l2Rows) {
                if (entity == null || !StringUtils.hasText(entity.getSourceMsgId())) {
                    continue;
                }
                String msgId = entity.getSourceMsgId();
                if (messageRepository.existsById(msgId)) {
                    continue;
                }
                count += deleteVectorOnce(
                        entity.getUserId(), entity.getTenantId(), msgId, deletedKeys);
            }
        }
        List<ConversationContextL1Entity> l1Rows = l1Repository.findAll();
        if (l1Rows != null) {
            for (ConversationContextL1Entity row : l1Rows) {
                if (row == null || !StringUtils.hasText(row.getConvId())) {
                    continue;
                }
                if (conversationRepository.existsById(row.getConvId())) {
                    continue;
                }
                for (String msgId : orphanMsgIdsFromL1(row)) {
                    count += deleteVectorOnce(
                            row.getUserId(), row.getTenantId(), msgId, deletedKeys);
                }
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

    private static Set<String> orphanMsgIdsFromL1(ConversationContextL1Entity row) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(row.getFarFoldedMsgIds())) {
            try {
                List<String> list = OM.readValue(row.getFarFoldedMsgIds(), LIST_TYPE);
                if (list != null) {
                    for (String id : list) {
                        if (StringUtils.hasText(id)) {
                            ids.add(id);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 解析失败跳过该字段
            }
        }
        if (StringUtils.hasText(row.getMidAnswers())) {
            try {
                Map<String, String> map = OM.readValue(row.getMidAnswers(), MAP_TYPE);
                if (map != null) {
                    for (String id : map.keySet()) {
                        if (StringUtils.hasText(id)) {
                            ids.add(id);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 解析失败跳过该字段
            }
        }
        return ids;
    }

    private int deleteVectorOnce(
            String userId, String tenantId, String msgId, Set<String> deletedKeys) {
        if (!StringUtils.hasText(msgId) || !StringUtils.hasText(userId)) {
            return 0;
        }
        String tid = tenantId != null ? tenantId : "default";
        String key = userId + "|" + tid + "|" + msgId;
        if (deletedKeys != null && !deletedKeys.add(key)) {
            return 0;
        }
        try {
            historyRagClient.delete(userId, tid, msgId).block();
            return 1;
        } catch (Exception e) {
            log.warn("[ContextMaintenance] L3 delete failed msg={}: {}", msgId, e.getMessage());
            return 0;
        }
    }
}
