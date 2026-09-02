package com.sunshine.tool.mcp;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.McpServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 独立事务写入 MCP 探测元数据，避免 probe 失败时主事务回滚导致 last_probe_at 未落库。 */
@Component
@RequiredArgsConstructor
public class McpProbeRecorder {

    private final McpServerRepository mcpServerRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String serverId, Instant probeAt, String status, String error) {
        McpServerEntity server = mcpServerRepository.findById(serverId)
                .orElseThrow(() -> new BizException(ToolErrorCode.MCP_SERVER_NOT_FOUND));
        server.setLastProbeAt(probeAt);
        server.setProbeStatus(status);
        server.setProbeError(error);
        server.setUpdatedAt(probeAt);
        mcpServerRepository.save(server);
    }
}
