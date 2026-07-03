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
@Table(name = "eval_suite_item")
@Getter
@Setter
public class EvalSuiteItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "suite_id", nullable = false)
    private Long suiteId;
    @Column(name = "item_key", nullable = false, length = 64)
    private String itemKey;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "query_text", nullable = false, length = 1024)
    private String queryText;
    @Column(name = "item_type", nullable = false, length = 16)
    private String itemType = "positive";
    @Column(name = "relevant_doc_ids", nullable = false, columnDefinition = "JSON")
    private String relevantDocIdsJson = "[]";
    @Column(name = "relevant_keywords", columnDefinition = "JSON")
    private String relevantKeywordsJson;
    @Column(length = 32)
    private String category;
    @Column(name = "expect_empty", nullable = false)
    private boolean expectEmpty;
    @Column(nullable = false, length = 16)
    private String status = "active";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
