package com.sunshine.tools.sdk.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvocationContextTest {

    @AfterEach
    void tearDown() {
        ToolInvocationContext.clear();
    }

    @Test
    void requireUserId_withoutContext_throws() {
        ToolInvocationContext.clear();
        assertThatThrownBy(ToolInvocationContext::requireUserId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireUserId_withUser_returnsIt() {
        ToolInvocationContext.set("t1", "u42");
        assertThat(ToolInvocationContext.requireUserId()).isEqualTo("u42");
    }

    @Test
    void tenantIdOrDefault_blankTenant_returnsDefault() {
        ToolInvocationContext.set("  ", "u1");
        assertThat(ToolInvocationContext.tenantIdOrDefault()).isEqualTo("default");
    }

    @Test
    void tenantIdOrDefault_unset_returnsDefault() {
        ToolInvocationContext.clear();
        assertThat(ToolInvocationContext.tenantIdOrDefault()).isEqualTo("default");
    }

    @Test
    void clear_removesContext() {
        ToolInvocationContext.set("t1", "u1");
        ToolInvocationContext.clear();
        assertThatThrownBy(ToolInvocationContext::requireUserId)
                .isInstanceOf(IllegalStateException.class);
        assertThat(ToolInvocationContext.tenantIdOrDefault()).isEqualTo("default");
    }
}
