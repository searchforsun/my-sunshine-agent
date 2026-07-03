package com.sunshine.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "eval_report")
@Getter
@Setter
public class EvalReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "job_id", nullable = false)
    private Long jobId;
    @Column(name = "recall_at_5")
    private Double recallAt5;
    private Double mrr;
    @Column(name = "delta_json", columnDefinition = "JSON")
    private String deltaJson;
    @Column(name = "summary_json", columnDefinition = "JSON")
    private String summaryJson;
    @Column(name = "failed_samples_json", columnDefinition = "JSON")
    private String failedSamplesJson;
    @Column(name = "suggestions_json", columnDefinition = "JSON")
    private String suggestionsJson;
    @Column(name = "baseline_recall_at_5")
    private Double baselineRecallAt5;
    @Column(name = "passed_gate")
    private Boolean passedGate;
    @Column(name = "report_md_path", length = 512)
    private String reportMdPath;
    @Column(name = "report_object_key", length = 512)
    private String reportObjectKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
