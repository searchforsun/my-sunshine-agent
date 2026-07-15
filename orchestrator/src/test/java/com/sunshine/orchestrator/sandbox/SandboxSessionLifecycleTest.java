package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.SandboxPolicy;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.client.SkillCatalogClient;
import com.sunshine.orchestrator.client.sandbox.CreateSessionRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SandboxSessionLifecycleTest {

    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private SkillCatalogClient skillCatalogClient;
    @Mock
    private SandboxClient sandboxClient;

    private SandboxSessionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new SandboxSessionLifecycle(skillCatalogService, skillCatalogClient, sandboxClient);
        SandboxSessionHolder.unbind();
    }

    @AfterEach
    void tearDown() {
        SandboxSessionHolder.unbind();
    }

    @Test
    void openIfNeeded_sandboxNone_skipsCreate() {
        when(skillCatalogService.find("plain")).thenReturn(Optional.of(
                new SkillCatalogEntry("plain", "P", "d", "o", 1, true, "none", null)));
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1", List.of(), "plain");

        lifecycle.openIfNeeded(req);

        verify(sandboxClient, never()).createSession(any());
        assertThat(SandboxSessionHolder.current()).isNull();
    }

    @Test
    void openAndClose_sandboxDocker_createAndCloseOnce() {
        SandboxPolicy policy = new SandboxPolicy(
                "docker", "sunshine-sandbox-python:3.11-slim", 30, 256, 0.5, List.of(), List.of("pwd"));
        when(skillCatalogService.find("coding")).thenReturn(Optional.of(
                new SkillCatalogEntry("coding", "C", "d", "o", 1, true, "docker", policy)));
        when(skillCatalogClient.fetchMaterial("coding")).thenReturn(Map.of("scripts/a.py", "print(1)"));
        when(sandboxClient.createSession(any())).thenReturn("sess-1");

        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1", List.of(), "coding");

        lifecycle.openIfNeeded(req);

        assertThat(SandboxSessionHolder.requireSessionId()).isEqualTo("sess-1");
        assertThat(SandboxSessionHolder.current().policy()).isEqualTo(policy);
        ArgumentCaptor<CreateSessionRequest> captor = ArgumentCaptor.forClass(CreateSessionRequest.class);
        verify(sandboxClient).createSession(captor.capture());
        assertThat(captor.getValue().skillId()).isEqualTo("coding");
        assertThat(captor.getValue().skillFiles()).containsEntry("scripts/a.py", "print(1)");
        assertThat(captor.getValue().runId()).isEqualTo(req.runId());

        lifecycle.closeQuietly();

        verify(sandboxClient).closeSession("sess-1");
        assertThat(SandboxSessionHolder.current()).isNull();
    }

    @Test
    void openIfNeeded_noSkillId_skips() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1");

        lifecycle.openIfNeeded(req);

        verify(sandboxClient, never()).createSession(any());
    }
}
