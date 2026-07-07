package com.sunshine.orchestrator.taskboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** ReAct TaskBoard 终态快照 */
@Entity
@Table(name = "react_task_board")
@Getter
@Setter
public class ReactTaskBoardEntity {

    @Id
    private String id;

    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "items_json", nullable = false, columnDefinition = "JSON")
    private String itemsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
