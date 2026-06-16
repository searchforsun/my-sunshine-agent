package com.sunshine.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdInjectGatewayFilterTest {

    @Test
    @DisplayName("白名单路径 login/register")
    void isPublicAuthPath() {
        assertThat(UserIdInjectGatewayFilter.isPublicAuthPath("/api/auth/login")).isTrue();
        assertThat(UserIdInjectGatewayFilter.isPublicAuthPath("/api/auth/register")).isTrue();
        assertThat(UserIdInjectGatewayFilter.isPublicAuthPath("/api/auth/me")).isFalse();
        assertThat(UserIdInjectGatewayFilter.isPublicAuthPath("/api/chat/stream")).isFalse();
    }

    @Test
    @DisplayName("auth-center 路径跳过 x-user-id 注入")
    void shouldSkipAuthCenterPaths() {
        assertThat(UserIdInjectGatewayFilter.shouldSkip("/api/auth/me")).isTrue();
        assertThat(UserIdInjectGatewayFilter.shouldSkip("/api/auth/logout")).isTrue();
        assertThat(UserIdInjectGatewayFilter.shouldSkip("/api/conversations")).isFalse();
    }
}
