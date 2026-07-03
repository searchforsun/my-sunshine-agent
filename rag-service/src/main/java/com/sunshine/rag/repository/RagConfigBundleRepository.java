package com.sunshine.rag.repository;

import com.sunshine.rag.entity.RagConfigBundleEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RagConfigBundleRepository extends JpaRepository<RagConfigBundleEntity, Long> {
    Optional<RagConfigBundleEntity> findByTenantIdAndKbId(String tenantId, String kbId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM RagConfigBundleEntity b WHERE b.tenantId = :tenantId AND b.kbId = :kbId")
    Optional<RagConfigBundleEntity> findByTenantIdAndKbIdForUpdate(
            @Param("tenantId") String tenantId,
            @Param("kbId") String kbId);
}
