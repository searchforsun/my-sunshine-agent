package com.sunshine.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelGatewayTenantConfigurationTest {

    @Test
    void blockJsonHasTenantMessage() {
        assertThat(SentinelGatewayTenantConfiguration.BLOCK_JSON).contains("429");
        assertThat(SentinelGatewayTenantConfiguration.BLOCK_JSON).contains("租户");
    }
}
