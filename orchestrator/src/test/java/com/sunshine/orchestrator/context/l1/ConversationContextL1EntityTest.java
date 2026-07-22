package com.sunshine.orchestrator.context.l1;

import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextL1EntityTest {

    @Test
    void tableName_isConversationContextL1() {
        assertThat(ConversationContextL1Entity.class.getAnnotation(Table.class).name())
                .isEqualTo("conversation_context_l1");
    }

    @Test
    void gettersSetters_roundTrip() {
        var e = new ConversationContextL1Entity();
        e.setConvId("c1");
        e.setUserId("u1");
        e.setTenantId("t1");
        e.setMidAnswers("{\"m1\":\"s\"}");
        e.setFarSummary("far");
        e.setNearN(6);
        e.setMidN(4);
        Instant now = Instant.parse("2026-07-22T02:00:00Z");
        e.setUpdatedAt(now);
        assertThat(e.getConvId()).isEqualTo("c1");
        assertThat(e.getUserId()).isEqualTo("u1");
        assertThat(e.getTenantId()).isEqualTo("t1");
        assertThat(e.getMidAnswers()).isEqualTo("{\"m1\":\"s\"}");
        assertThat(e.getFarSummary()).isEqualTo("far");
        assertThat(e.getNearN()).isEqualTo(6);
        assertThat(e.getMidN()).isEqualTo(4);
        assertThat(e.getUpdatedAt()).isEqualTo(now);
    }
}
