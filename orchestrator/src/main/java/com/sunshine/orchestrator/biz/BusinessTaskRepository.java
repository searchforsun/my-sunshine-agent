package com.sunshine.orchestrator.biz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 业务任务板召回（authority §4.1）：仅按 (tenant, user, biz_scene, status, 时间窗) SQL 精确匹配，
 * 禁止向量相似度决定焦点任务。
 */
public interface BusinessTaskRepository extends JpaRepository<BusinessTaskEntity, String> {

    /** 候选池：同场景活跃任务按更新时间倒序（含时间窗）。 */
    List<BusinessTaskEntity> findByTenantIdAndUserIdAndBizSceneAndStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
            String tenantId, String userId, String bizScene, Collection<String> status, Instant updatedAtAfter);

    /** 锚定：当前会话已绑定的活跃任务。 */
    Optional<BusinessTaskEntity> findFirstByConversationIdAndStatusIn(String conversationId, Collection<String> status);
}
