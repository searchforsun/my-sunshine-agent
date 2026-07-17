package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.client.SkillCatalogClient;
import com.sunshine.orchestrator.client.sandbox.CreateSessionRequest;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.memory.MemoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SandboxSessionLifecycleTest {

    @Mock
    private SkillCatalogClient skillCatalogClient;
    @Mock
    private SandboxClient sandboxClient;
    @Mock
    private ConversationSandboxStore conversationSandboxStore;

    private AgentSandboxProperties sandboxProperties;
    private SandboxSessionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        sandboxProperties = new AgentSandboxProperties();
        lifecycle = new SandboxSessionLifecycle(
                skillCatalogClient, sandboxClient, conversationSandboxStore, sandboxProperties);
        SandboxSessionHolder.clearAll();
    }

    @AfterEach
    void tearDown() {
        SandboxSessionHolder.clearAll();
    }

    @Test
    void prepareRun_doesNotCreateSession() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1", List.of(), null, false, "conv-1");
        lifecycle.prepareRun(req);
        verify(sandboxClient, never()).createSession(any());
        assertThat(SandboxSessionHolder.get(req.resolveBridgeId())).isNull();
    }

    @Test
    void ensureBound_withoutSkill_createsEmptySession() {
        when(sandboxClient.createSession(any())).thenReturn("sess-1");
        when(conversationSandboxStore.find(anyString(), anyString())).thenReturn(Optional.empty());

        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1", List.of(), null, false, "conv-1");
        lifecycle.prepareRun(req);

        assertThat(lifecycle.ensureBound(req.resolveBridgeId())).isEqualTo("sess-1");
        ArgumentCaptor<CreateSessionRequest> captor = ArgumentCaptor.forClass(CreateSessionRequest.class);
        verify(sandboxClient).createSession(captor.capture());
        assertThat(captor.getValue().skillFiles()).isEmpty();
        verify(sandboxClient, never()).mountSkill(anyString(), anyString(), any());
        verify(conversationSandboxStore).save(any());

        lifecycle.closeQuietly(req);
        verify(sandboxClient, never()).closeSession(anyString());
        assertThat(SandboxSessionHolder.get(req.resolveBridgeId())).isNull();
    }

    @Test
    void ensureBound_withSkill_mountsMaterial() {
        when(skillCatalogClient.fetchMaterial("coding")).thenReturn(Map.of("scripts/a.py", "print(1)"));
        when(sandboxClient.createSession(any())).thenReturn("sess-1");
        when(conversationSandboxStore.find(anyString(), anyString())).thenReturn(Optional.empty());

        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1", List.of(), "coding",
                false, "conv-1");
        lifecycle.prepareRun(req);
        assertThat(lifecycle.ensureBound(req.resolveBridgeId())).isEqualTo("sess-1");
        verify(sandboxClient).mountSkill(eq("sess-1"), eq("coding"), any());
        lifecycle.closeQuietly(req);
    }

    @Test
    void ensureBound_reusesConversationSession_andMountsNewSkill() {
        when(skillCatalogClient.fetchMaterial("coding-b")).thenReturn(Map.of("scripts/b.py", "print(2)"));
        when(conversationSandboxStore.find("default", "conv-x")).thenReturn(Optional.of(
                new ConversationSandboxBinding(
                        "sess-reuse", List.of("coding-a"), "u1", "default", "conv-x")));
        when(sandboxClient.sessionAlive("sess-reuse")).thenReturn(true);

        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-x", List.of(), "coding-b",
                false, "conv-x");
        lifecycle.prepareRun(req);
        assertThat(lifecycle.ensureBound(req.resolveBridgeId())).isEqualTo("sess-reuse");
        verify(sandboxClient, never()).createSession(any());
        verify(sandboxClient).mountSkill(eq("sess-reuse"), eq("coding-b"), any());
        verify(conversationSandboxStore).save(any());
        lifecycle.closeQuietly(req);
    }

    @Test
    void ensureConversationSession_createsWithoutSkill() {
        when(sandboxClient.createSession(any())).thenReturn("sess-w");
        when(conversationSandboxStore.find("default", "conv-w")).thenReturn(Optional.empty());

        assertThat(lifecycle.ensureConversationSession("u1", "default", "conv-w", null))
                .isEqualTo("sess-w");
        verify(sandboxClient, never()).mountSkill(anyString(), anyString(), any());
    }
}
