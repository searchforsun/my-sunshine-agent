package com.sunshine.orchestrator.usage.repo;

import com.sunshine.orchestrator.usage.entity.LlmUsageRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LlmUsageRecordRepository extends JpaRepository<LlmUsageRecordEntity, Long> {

    @Query("""
            SELECT u FROM LlmUsageRecordEntity u
            WHERE (:since IS NULL OR u.requestAt >= :since)
              AND (:until IS NULL OR u.requestAt < :until)
              AND (:model IS NULL OR u.model = :model)
              AND (:tenantId IS NULL OR u.tenantId = :tenantId)
            ORDER BY u.requestAt DESC
            """)
    List<LlmUsageRecordEntity> search(
            @Param("since") Instant since,
            @Param("until") Instant until,
            @Param("model") String model,
            @Param("tenantId") String tenantId);

    /** 按 model 聚合 token 用量（5.2.3 简化版：调用数与 token 汇总，成本估算后置）。 */
    @Query("""
            SELECT u.model AS model,
                   COUNT(u) AS calls,
                   SUM(u.totalTokens) AS totalTokens,
                   SUM(u.promptTokens) AS promptTokens,
                   SUM(u.completionTokens) AS completionTokens
            FROM LlmUsageRecordEntity u
            WHERE (:since IS NULL OR u.requestAt >= :since)
              AND (:until IS NULL OR u.requestAt < :until)
              AND (:tenantId IS NULL OR u.tenantId = :tenantId)
            GROUP BY u.model
            ORDER BY totalTokens DESC
            """)
    List<ModelUsageView> aggregateByModel(
            @Param("since") Instant since,
            @Param("until") Instant until,
            @Param("tenantId") String tenantId);

    interface ModelUsageView {
        String getModel();

        Long getCalls();

        Long getTotalTokens();

        Long getPromptTokens();

        Long getCompletionTokens();
    }

    /** 按自然日/租户/模型/调用点聚合明细（5.2.3 日聚合任务数据源，原生 SQL 取 DATE(request_at)）。 */
    @Query(value = """
            SELECT DATE(u.request_at) AS statDate,
                   u.tenant_id          AS tenantId,
                   u.model              AS model,
                   u.call_site          AS callSite,
                   COUNT(*)             AS calls,
                   SUM(u.prompt_tokens) AS promptTokens,
                   SUM(u.completion_tokens) AS completionTokens,
                   SUM(u.total_tokens)  AS totalTokens
            FROM llm_usage_record u
            WHERE u.request_at >= :from AND u.request_at < :to
            GROUP BY DATE(u.request_at), u.tenant_id, u.model, u.call_site
            """, nativeQuery = true)
    List<DailyAggView> aggregateDaily(@Param("from") Instant from, @Param("to") Instant to);

    /** 租户在 [from, to) 区间的 token 总量（5.2.4 配额校验月度用量）。 */
    @Query("""
            SELECT COALESCE(SUM(u.totalTokens), 0) FROM LlmUsageRecordEntity u
            WHERE u.requestAt >= :from AND u.requestAt < :to AND u.tenantId = :tenantId
            """)
    long sumTokensBetween(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("tenantId") String tenantId);

    interface DailyAggView {
        java.sql.Date getStatDate();

        String getTenantId();

        String getModel();

        String getCallSite();

        Long getCalls();

        Long getPromptTokens();

        Long getCompletionTokens();

        Long getTotalTokens();
    }
}
