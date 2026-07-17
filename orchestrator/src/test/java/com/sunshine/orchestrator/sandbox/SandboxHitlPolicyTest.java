package com.sunshine.orchestrator.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxHitlPolicyTest {

    @AfterEach
    void tearDown() {
        SandboxSessionHolder.clearAll();
    }

    @Test
    void requiresConfirmation_readWriteAndExecWhitelist() {
        assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.READ, Map.of())).isFalse();
        assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.WRITE, Map.of())).isTrue();
        assertThat(SandboxHitlPolicy.requiresConfirmation(SandboxIds.EXEC, Map.of("command", "ls"))).isFalse();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.EXEC, Map.of("command", "rm -rf /workspace"))).isTrue();
    }

    @Test
    void catalogDefault_execTrue_readFalse() {
        assertThat(SandboxHitlPolicy.catalogDefault(SandboxIds.READ)).isFalse();
        assertThat(SandboxHitlPolicy.catalogDefault(SandboxIds.GLOB)).isFalse();
        assertThat(SandboxHitlPolicy.catalogDefault(SandboxIds.GREP)).isFalse();
        assertThat(SandboxHitlPolicy.catalogDefault(SandboxIds.WRITE)).isTrue();
        assertThat(SandboxHitlPolicy.catalogDefault(SandboxIds.EDIT)).isTrue();
        assertThat(SandboxHitlPolicy.catalogDefault(SandboxIds.EXEC)).isTrue();
    }

    @Test
    void isReadonlyExec_defaultAllow() {
        assertThat(SandboxHitlPolicy.isReadonlyExec("pwd", null)).isTrue();
        assertThat(SandboxHitlPolicy.isReadonlyExec("python -m pytest -q", null)).isTrue();
        assertThat(SandboxHitlPolicy.isReadonlyExec("rm -rf /", null)).isFalse();
    }

    @Test
    void writeHitlMode_matrix() {
        Map<String, Object> empty = Map.of();
        Map<String, Object> danger = Map.of("command", "python3 -c 'print(1)'");
        Map<String, Object> readonly = Map.of("command", "ls");

        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.WRITE, empty, SandboxWriteHitlMode.NEVER)).isTrue();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.WRITE, empty, SandboxWriteHitlMode.ALWAYS)).isFalse();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.WRITE, empty, SandboxWriteHitlMode.SMART)).isFalse();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.EDIT, empty, SandboxWriteHitlMode.SMART)).isFalse();

        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.EXEC, danger, SandboxWriteHitlMode.NEVER)).isTrue();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.EXEC, danger, SandboxWriteHitlMode.SMART)).isTrue();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.EXEC, danger, SandboxWriteHitlMode.ALWAYS)).isFalse();
        assertThat(SandboxHitlPolicy.requiresConfirmation(
                SandboxIds.EXEC, readonly, SandboxWriteHitlMode.SMART)).isFalse();
    }

    @Test
    void writeHitlMode_fromWire() {
        assertThat(SandboxWriteHitlMode.from(null)).isEqualTo(SandboxWriteHitlMode.NEVER);
        assertThat(SandboxWriteHitlMode.from("smart")).isEqualTo(SandboxWriteHitlMode.SMART);
        assertThat(SandboxWriteHitlMode.from("ALWAYS")).isEqualTo(SandboxWriteHitlMode.ALWAYS);
        assertThat(SandboxWriteHitlMode.from("bogus")).isEqualTo(SandboxWriteHitlMode.NEVER);
    }
}
