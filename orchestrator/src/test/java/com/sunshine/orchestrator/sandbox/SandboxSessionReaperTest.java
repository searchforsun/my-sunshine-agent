package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SandboxSessionReaperTest {

    @Mock
    private ConversationSandboxStore store;
    @Mock
    private SandboxClient sandboxClient;

    private SandboxSessionReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new SandboxSessionReaper(store, sandboxClient);
    }

    @Test
    void reapIdleStop_stopsWithoutClose() {
        when(store.pollExpiredMembers(1000L)).thenReturn(Set.of("sess-1|default|conv-1"));

        reaper.reapIdleStop(1000L);

        verify(sandboxClient).stopSession("sess-1");
        verify(sandboxClient, never()).closeSession(anyString());
        verify(store).markStopped("default", "conv-1");
        verify(store).removeExpiryMember("sess-1|default|conv-1");
    }

    @Test
    void reapPurgeDestroy_closesAndRemoves() {
        when(store.pollPurgeMembers(2000L)).thenReturn(Set.of("sess-2|default|conv-2"));

        reaper.reapPurgeDestroy(2000L);

        verify(store).remove("default", "conv-2");
        verify(sandboxClient).closeSession("sess-2");
        verify(sandboxClient, never()).stopSession(eq("sess-2"));
        verify(store).removePurgeMember("sess-2|default|conv-2");
    }
}
