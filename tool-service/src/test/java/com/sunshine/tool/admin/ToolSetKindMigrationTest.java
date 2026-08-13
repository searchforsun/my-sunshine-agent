package com.sunshine.tool.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSetKindMigrationTest {

    @Test
    void fromPath_acceptsNewAndLegacy() {
        assertThat(ToolSetKind.fromPath("chat")).isEqualTo(ToolSetKind.CHAT_DEFAULT);
        assertThat(ToolSetKind.fromPath("react-default")).isEqualTo(ToolSetKind.CHAT_DEFAULT);
        assertThat(ToolSetKind.fromPath("task")).isEqualTo(ToolSetKind.TASK_DEFAULT);
        assertThat(ToolSetKind.fromPath("plan-workflow")).isEqualTo(ToolSetKind.TASK_DEFAULT);
    }

    @Test
    void pathWire_isChatOrTaskOnly() {
        assertThat(ToolSetKind.CHAT_DEFAULT.path()).isEqualTo("chat");
        assertThat(ToolSetKind.TASK_DEFAULT.path()).isEqualTo("task");
    }
}
