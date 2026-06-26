package com.sunshine.common.web.health;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void healthReturnsUp() {
        HealthController controller = new HealthController("sunshine-bff");
        Map<String, String> body = controller.health();
        assertThat(body).containsEntry("status", "UP");
        assertThat(body).containsEntry("service", "sunshine-bff");
    }
}
