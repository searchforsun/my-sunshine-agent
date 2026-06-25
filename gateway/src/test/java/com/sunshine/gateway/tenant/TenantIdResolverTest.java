package com.sunshine.gateway.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIdResolverTest {

    @Test
    void forRateLimitUsesAnonymousWhenNotLoggedIn() {
        assertThat(TenantIdResolver.forRateLimit(false)).isEqualTo(TenantIdResolver.ANONYMOUS);
    }

    @Test
    void constants() {
        assertThat(TenantIdResolver.DEFAULT_TENANT).isEqualTo("default");
        assertThat(TenantIdResolver.ANONYMOUS).isEqualTo("anonymous");
    }
}
