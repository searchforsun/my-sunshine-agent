package com.sunshine.orchestrator.usage.repo;

import com.sunshine.orchestrator.usage.entity.LlmUsageDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LlmUsageDailyRepository extends JpaRepository<LlmUsageDailyEntity, Long> {

    /** 按日/租户/模型/调用点聚合查询（5.2.3 / 5.2.5 用量页）。 */
    @Query("""
            SELECT d FROM LlmUsageDailyEntity d
            WHERE (:since IS NULL OR d.statDate >= :since)
              AND (:until IS NULL OR d.statDate < :until)
              AND (:tenantId IS NULL OR d.tenantId = :tenantId)
              AND (:model IS NULL OR d.model = :model)
            ORDER BY d.statDate ASC, d.totalTokens DESC
            """)
    List<LlmUsageDailyEntity> searchDaily(
            @Param("since") LocalDate since,
            @Param("until") LocalDate until,
            @Param("tenantId") String tenantId,
            @Param("model") String model);

    /** 重建聚合区间：先删后插保证幂等（聚合任务每次处理 [since, until) 全量重建）。 */
    @Modifying
    @Query("""
            DELETE FROM LlmUsageDailyEntity d
            WHERE d.statDate >= :since AND d.statDate < :until
            """)
    int deleteRange(@Param("since") LocalDate since, @Param("until") LocalDate until);
}
