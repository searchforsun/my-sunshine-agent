package com.sunshine.orchestrator.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalancedWebClientTransportTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void withCallSite_injectsIntoJsonBody() throws Exception {
        String body = "{\"model\":\"deepseek-v4-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String out = (String) LoadBalancedWebClientTransport.withCallSite(body, "chat");
        JsonNode node = om.readTree(out);
        assertThat(node.get("model").asText()).isEqualTo("deepseek-v4-pro");
        assertThat(node.get("call_site").asText()).isEqualTo("chat");
        assertThat(node.get("messages").size()).isEqualTo(1);
    }

    @Test
    void withCallSite_nullOrBlankCallSiteReturnsBody() {
        String body = "{\"model\":\"m\"}";
        assertThat(LoadBalancedWebClientTransport.withCallSite(body, null)).isEqualTo(body);
        assertThat(LoadBalancedWebClientTransport.withCallSite(body, "  ")).isEqualTo(body);
        assertThat(LoadBalancedWebClientTransport.withCallSite(null, "chat")).isNull();
    }

    @Test
    void withCallSite_nonJsonBodyReturnsAsIs() {
        String body = "not-json";
        assertThat(LoadBalancedWebClientTransport.withCallSite(body, "chat")).isEqualTo(body);
    }

    @Test
    void withCallSite_keepsExistingCallSiteUntouched() throws Exception {
        String body = "{\"call_site\":\"rewrite\",\"model\":\"m\"}";
        String out = (String) LoadBalancedWebClientTransport.withCallSite(body, "chat");
        assertThat(om.readTree(out).get("call_site").asText()).isEqualTo("chat");
    }
}
