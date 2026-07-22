package com.sunshine.orchestrator.context.l2;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextStateEntityTest {

    @Test
    void tableName_isUserContextState() {
        assertThat(UserContextStateEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("user_context_state");
    }

    @Test
    void stateKey_mapsToStateKeyColumn() throws NoSuchFieldException {
        Column col = UserContextStateEntity.class.getDeclaredField("stateKey")
                .getAnnotation(Column.class);
        assertThat(col.name()).isEqualTo("state_key");
    }

    @Test
    void gettersSetters_roundTrip() {
        var e = new UserContextStateEntity();
        e.setId("id1");
        e.setUserId("u1");
        e.setTenantId("t1");
        e.setKind("preference");
        e.setStateKey("style");
        e.setStateValue("简洁");
        e.setConfidence(0.9);
        e.setStatus("active");
        Instant now = Instant.parse("2026-07-22T02:00:00Z");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e.setSourceMsgId("m1");
        assertThat(e.getStateKey()).isEqualTo("style");
        assertThat(e.getStateValue()).isEqualTo("简洁");
        assertThat(e.getKind()).isEqualTo("preference");
        assertThat(e.getConfidence()).isEqualTo(0.9);
        assertThat(e.getStatus()).isEqualTo("active");
        assertThat(e.getSourceMsgId()).isEqualTo("m1");
    }
}
