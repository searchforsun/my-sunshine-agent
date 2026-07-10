package com.sunshine.tool.repo;

import com.sunshine.tool.entity.ToolDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ToolDefinitionRepository extends JpaRepository<ToolDefinitionEntity, String> {

    Optional<ToolDefinitionEntity> findBySourceAndSourceRefAndExternalName(
            String source, String sourceRef, String externalName);

    List<ToolDefinitionEntity> findBySourceAndSourceRef(String source, String sourceRef);

    @Query("""
            SELECT t FROM ToolDefinitionEntity t
            WHERE (t.tenantId = :tenantId OR t.tenantId = 'default')
              AND (:enabledOnly = false OR (t.enabled = true AND t.idValid = true))
            ORDER BY t.id
            """)
    List<ToolDefinitionEntity> findVisibleForTenant(
            @Param("tenantId") String tenantId,
            @Param("enabledOnly") boolean enabledOnly);

    @Query("""
            SELECT t FROM ToolDefinitionEntity t
            WHERE t.id = :toolId
              AND (t.tenantId = :tenantId OR t.tenantId = 'default')
            """)
    Optional<ToolDefinitionEntity> findVisibleById(
            @Param("toolId") String toolId,
            @Param("tenantId") String tenantId);
}
