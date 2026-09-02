package com.sunshine.tool.repo;

import com.sunshine.tool.entity.ToolSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ToolSetRepository extends JpaRepository<ToolSetEntity, String> {

    @Query("""
            SELECT t FROM ToolSetEntity t
            WHERE t.setType = :setType
              AND ((:tenantId IS NULL AND t.tenantId IS NULL) OR t.tenantId = :tenantId)
            """)
    Optional<ToolSetEntity> findBySetTypeAndTenantId(
            @Param("setType") String setType,
            @Param("tenantId") String tenantId);
}
