package com.sunshine.rag.repository;

import com.sunshine.rag.entity.EvalSuiteItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvalSuiteItemRepository extends JpaRepository<EvalSuiteItemEntity, Long> {
    List<EvalSuiteItemEntity> findBySuiteIdOrderBySortOrderAscItemKeyAsc(Long suiteId);
    Optional<EvalSuiteItemEntity> findBySuiteIdAndItemKey(Long suiteId, String itemKey);
    int countBySuiteId(Long suiteId);
    void deleteBySuiteId(Long suiteId);
}
