package com.sunshine.rag.repository;

import com.sunshine.rag.entity.RagConfigVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagConfigVersionRepository extends JpaRepository<RagConfigVersionEntity, Long> {
    Optional<RagConfigVersionEntity> findByIdAndBundleId(Long id, Long bundleId);

    List<RagConfigVersionEntity> findByBundleIdOrderByVersionNoDesc(Long bundleId);

    Optional<RagConfigVersionEntity> findFirstByBundleIdOrderByVersionNoDesc(Long bundleId);

    Optional<RagConfigVersionEntity> findFirstByBundleIdAndStatusOrderByVersionNoDesc(Long bundleId, String status);

    List<RagConfigVersionEntity> findByBundleIdAndStatus(Long bundleId, String status);

    List<RagConfigVersionEntity> findByBundleIdAndStatusIn(Long bundleId, List<String> statuses);
}
