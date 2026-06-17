package com.sunshine.tool.tool;

import com.sunshine.tool.client.FinanceServiceClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinanceTool {

    private final FinanceServiceClient financeClient;

    @Tool(name = "list_finance_messages",
            description = "查询财务待办/审批消息列表。当用户询问报销、付款、预算、退款、待审批消息时使用。"
                    + "status 可选 pending（待审批）、approved（已通过）、all（全部）。")
    public String listFinanceMessages(
            @ToolParam(name = "status", description = "pending | approved | all，默认 all")
            String status) {
        log.info("[FinanceTool] list_finance_messages status={}", status);
        return financeClient.listMessagesText(status);
    }
}
