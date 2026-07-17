package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSandboxBindingJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTrip_doesNotPersistStoppedAccessor() throws Exception {
        ConversationSandboxBinding b = new ConversationSandboxBinding(
                "sess-1", List.of(), "u1", "default", "conv-1",
                ConversationSandboxBinding.STATE_RUNNING, 123L);
        String json = mapper.writeValueAsString(b);
        assertThat(json).doesNotContain("\"stopped\"");
        ConversationSandboxBinding back = mapper.readValue(json, ConversationSandboxBinding.class);
        assertThat(back.sessionId()).isEqualTo("sess-1");
        assertThat(back.state()).isEqualTo("running");
        assertThat(back.isStopped()).isFalse();
    }

    @Test
    void read_ignoresUnknownStoppedField() throws Exception {
        String json = """
                {"sessionId":"sess-1","loadedSkillIds":[],"userId":"u1","tenantId":"default",\
                "conversationId":"conv-1","state":"running","purgeAtEpochMs":1,"stopped":false}
                """;
        ConversationSandboxBinding back = mapper.readValue(json, ConversationSandboxBinding.class);
        assertThat(back.sessionId()).isEqualTo("sess-1");
        assertThat(back.isStopped()).isFalse();
    }
}
