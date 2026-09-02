package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CancellableToolRunRegistryTest {

    @Mock
    private SandboxClient sandboxClient;

    private CancellableToolRunRegistry registry;

    @BeforeEach
    void setUp() {
        AgentSandboxProperties props = new AgentSandboxProperties();
        registry = new CancellableToolRunRegistry(sandboxClient, props);
    }

    @Test
    void cancel_marksAndCallsSandbox() {
        registry.register("tu-1", "msg-1", SandboxIds.EXEC, "sess-1", "tu-1", "sleep 9");
        assertThat(registry.cancel("tu-1")).isTrue();
        assertThat(registry.isCancelled("tu-1")).isTrue();
        verify(sandboxClient).cancelInvocation("sess-1", "tu-1");
    }

    @Test
    void sameCommand_afterCancel_rejected() {
        // v17.13 方案 A：取消后同命令原样重试禁绝
        registry.register("tu-0", "msg-1", SandboxIds.EXEC, "sess-1", "tu-0", "sleep 9");
        registry.cancel("tu-0");
        registry.unregister("tu-0");

        assertThat(registry.tryConsumeFollowup("msg-1", SandboxIds.EXEC, Map.of("command", "sleep 9")))
                .isFalse();
    }

    @Test
    void differentCommandOrTool_afterCancel_allowed() {
        registry.register("tu-0", "msg-1", SandboxIds.EXEC, "sess-1", "tu-0", "sleep 9");
        registry.cancel("tu-0");
        registry.unregister("tu-0");

        assertThat(registry.tryConsumeFollowup("msg-1", SandboxIds.EXEC, Map.of("command", "ls -la")))
                .isTrue();
        assertThat(registry.tryConsumeFollowup("msg-1", SandboxIds.GREP, Map.of("pattern", "TODO")))
                .isTrue();
        assertThat(registry.tryConsumeFollowup("msg-1", SandboxIds.GLOB, Map.of("pattern", "*.java")))
                .isTrue();
    }

    @Test
    void withoutCancel_notBlocked() {
        assertThat(registry.tryConsumeFollowup("msg-x", SandboxIds.EXEC, Map.of("command", "sleep 9")))
                .isTrue();
        assertThat(registry.tryConsumeFollowup("msg-x", SandboxIds.EXEC, Map.of("command", "sleep 9")))
                .isTrue();
    }

    @Test
    void pendingCancel_scopedByMessageId() {
        assertThat(registry.markPendingCancel("tu-p2", "msg-c")).isTrue();
        registry.register("tu-p2", "msg-other", SandboxIds.EXEC, null, "tu-p2");
        assertThat(registry.isCancelled("tu-p2")).isFalse();

        assertThat(registry.markPendingCancel("tu-p4", "msg-d")).isTrue();
        registry.register("tu-p4", "msg-d", SandboxIds.EXEC, null, "tu-p4");
        assertThat(registry.isCancelled("tu-p4")).isTrue();
        assertThat(registry.consumeRecentlyCancelled("tu-p4")).isTrue();
    }

    @Test
    void cancel_unknownWithoutPending_returnsFalse() {
        assertThat(registry.cancel("missing")).isFalse();
    }

    @Test
    void register_blankMessageId_skipped() {
        registry.register("tu-blank", "  ", SandboxIds.EXEC, null, "tu-blank");
        assertThat(registry.get("tu-blank")).isNull();
    }

    @Test
    void register_storesExpandDetail_forControllerPause() {
        registry.register("tu-e", "msg-1", SandboxIds.EXEC, null, "tu-e", "sleep 9");
        assertThat(registry.get("tu-e").expandDetail()).isEqualTo("sleep 9");
        registry.bindExpandDetail("tu-e", "sleep 99");
        assertThat(registry.get("tu-e").expandDetail()).isEqualTo("sleep 99");
    }
}
