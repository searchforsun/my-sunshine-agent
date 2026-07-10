package com.sunshine.tool.mcp;

import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.repo.McpServerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(McpProbeRecorder.class)
@ActiveProfiles("test")
class McpProbeRecorderTest {

    @Autowired
    private McpProbeRecorder recorder;

    @Autowired
    private McpServerRepository mcpServerRepository;

    @Test
    void record_persistsProbeMetadataInNewTransaction() {
        McpServerEntity server = new McpServerEntity();
        server.setId("demo-probe");
        server.setTransport("stdio");
        server.setEnabled(true);
        server.setTenantId("default");
        server.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        server.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        mcpServerRepository.save(server);

        Instant probeAt = Instant.parse("2026-07-10T02:59:27Z");
        recorder.record("demo-probe", probeAt, "error", "timeout");

        McpServerEntity updated = mcpServerRepository.findById("demo-probe").orElseThrow();
        assertThat(updated.getLastProbeAt()).isEqualTo(probeAt);
        assertThat(updated.getProbeStatus()).isEqualTo("error");
        assertThat(updated.getProbeError()).isEqualTo("timeout");
    }
}
