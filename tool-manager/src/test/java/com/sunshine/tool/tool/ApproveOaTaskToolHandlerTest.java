package com.sunshine.tool.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveOaTaskToolHandlerTest {

    @Test
    void sideEffect_isWrite() {
        ApproveOaTaskToolHandler handler = new ApproveOaTaskToolHandler();
        assertThat(handler.sideEffect()).isEqualTo("write");
        assertThat(handler.name()).isEqualTo("approve_oa_task");
    }
}
