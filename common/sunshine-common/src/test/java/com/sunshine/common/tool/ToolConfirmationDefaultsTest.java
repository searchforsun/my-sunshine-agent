package com.sunshine.common.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolConfirmationDefaultsTest {

    @Test
    void fromSideEffect_writeRequiresConfirmation() {
        assertThat(ToolConfirmationDefaults.fromSideEffect("write")).isTrue();
        assertThat(ToolConfirmationDefaults.fromSideEffect("read")).isFalse();
    }
}
