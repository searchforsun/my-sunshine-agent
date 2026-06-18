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

    @Tool(name = "get_finance_message_detail",
            description = "按 id 查询单条财务消息详情。用户指定消息编号或要查看某条报销/付款详情时使用。")
    public String getFinanceMessageDetail(
            @ToolParam(name = "id", description = "财务消息 id，如 1001")
            String id) {
        log.info("[FinanceTool] get_finance_message_detail id={}", id);
        return financeClient.getMessageDetailText(id);
    }

    @Tool(name = "summarize_finance_by_status",
            description = "按状态统计财务消息条数与金额合计。用户问有多少待审批、总额多少时使用。"
                    + "status 可选 pending / approved / all。")
    public String summarizeFinanceByStatus(
            @ToolParam(name = "status", description = "pending | approved | all，默认 all")
            String status) {
        log.info("[FinanceTool] summarize_finance_by_status status={}", status);
        return financeClient.summarizeMessagesText(status);
    }
}
