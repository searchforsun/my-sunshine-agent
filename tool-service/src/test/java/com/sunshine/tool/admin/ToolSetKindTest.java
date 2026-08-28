package com.sunshine.tool.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSetKindTest {

    @Test
    void fromPath_acceptsChatAndTask() {
        assertThat(ToolSetKind.fromPath("chat")).isEqualTo(ToolSetKind.CHAT_DEFAULT);
        assertThat(ToolSetKind.fromPath("task")).isEqualTo(ToolSetKind.TASK_DEFAULT);
    }

    @Test
    void fromPath_acceptsAllUnionView() {
        assertThat(ToolSetKind.fromPath("all")).isEqualTo(ToolSetKind.ALL_DEFAULT);
    }

    @Test
    void fromPath_rejectsLegacyKinds() {
        assertThatThrownBy(() -> ToolSetKind.fromPath("react-default"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSetKind.fromPath("plan-workflow"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathWire_isChatOrTaskOnly() {
        assertThat(ToolSetKind.CHAT_DEFAULT.path()).isEqualTo("chat");
        assertThat(ToolSetKind.TASK_DEFAULT.path()).isEqualTo("task");
    }
}
