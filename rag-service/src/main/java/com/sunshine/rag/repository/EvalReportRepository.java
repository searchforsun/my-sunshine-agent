package com.sunshine.rag.repository;

import com.sunshine.rag.entity.EvalReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EvalReportRepository extends JpaRepository<EvalReportEntity, Long> {

    Optional<EvalReportEntity> findFirstByPassedGateTrueOrderByCreatedAtDesc();
}
