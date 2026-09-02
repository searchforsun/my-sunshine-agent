package com.sunshine.prompt.entity;

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
@Table(name = "prompt_version")
@Getter
@Setter
public class PromptVersionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "prompt_id", length = 128, nullable = false)
    private String promptId;
    @Column(nullable = false)
    private int version;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(name = "content_text", columnDefinition = "MEDIUMTEXT")
    private String contentText;
    @Column(name = "content_json", columnDefinition = "MEDIUMTEXT")
    private String contentJson;
    @Column(name = "change_note", length = 512)
    private String changeNote;
    @Column(length = 64)
    private String maintainer;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
