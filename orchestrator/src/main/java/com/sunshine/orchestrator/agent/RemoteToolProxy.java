package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.ToolManagerClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 远程工具代理 — 统一经 tool-manager 调用，替代 per-tool *AgentTool 包装类
 */
@Slf4j
@RequiredArgsConstructor
public class RemoteToolProxy {

    private final ToolManagerClient toolManagerClient;

    @Tool(name = "list_finance_messages",
            description = "查询财务待办/审批消息。用户问报销、付款、预算、待审批消息时调用。"
                    + "status: pending|approved|all")
    public String listFinanceMessages(
            @ToolParam(name = "status", description = "pending | approved | all")
            String status) {
        String normalized = status == null || status.isBlank() ? "all" : status;
        log.info("[RemoteToolProxy] invoke list_finance_messages status={}", normalized);
        return toolManagerClient.invoke("list_finance_messages", Map.of("status", normalized));
    }
}
