package com.sunshine.finance.tools;

import com.sunshine.finance.dto.FinanceMessageSummaryVO;
import com.sunshine.finance.dto.FinanceMessageVO;
import com.sunshine.finance.service.FinanceMessageService;
import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FinanceSunshineTools {

    private final FinanceMessageService messageService;

    @SunshineTool(
            id = "list_finance_messages",
            displayName = "查询待审批财务消息",
            description = "查询财务待办/审批消息。用户问报销、付款、预算、待审批消息时调用。status: pending|approved|all",
            timelineSummaryTemplate = "{count} 条财务消息",
            timelineSummaryExtract = "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}")
    public String listFinanceMessages(
            @ToolParam(value = "status", description = "pending | approved | all", required = false) String status) {
        List<FinanceMessageVO> messages = messageService.list(status != null ? status : "all");
        if (messages.isEmpty()) {
            return "未查询到符合条件的财务消息。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(messages.size()).append(" 条财务消息：\n");
        for (FinanceMessageVO msg : messages) {
            sb.append("- [").append(msg.id()).append("] ")
                    .append(msg.title())
                    .append(" | 类型=").append(msg.type())
                    .append(" | 状态=").append(msg.status())
                    .append(" | 金额=").append(msg.amount())
                    .append(" | 申请人=").append(msg.applicant())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @SunshineTool(
            id = "get_finance_message_detail",
            displayName = "查询财务消息详情",
            description = "按 id 查询单条财务消息详情。用户指定消息编号或要查看某条报销/付款详情时使用。",
            timelineSummaryTemplate = "{title}",
            timelineSummaryExtract = "{\"title\":\"regex:-\\\\s*标题=(.+)\"}")
    public String getFinanceMessageDetail(
            @ToolParam(value = "id", description = "财务消息 id，如 1001") String id) {
        if (id == null || id.isBlank()) {
            return "请提供财务消息 id。";
        }
        long messageId;
        try {
            messageId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return "未找到 id=" + id + " 的财务消息。";
        }
        return messageService.getById(messageId)
                .map(msg -> """
                        财务消息详情：
                        - id=%s
                        - 标题=%s
                        - 类型=%s
                        - 状态=%s
                        - 金额=%s
                        - 申请人=%s
                        - 创建时间=%s
                        """.formatted(
                        msg.id(), msg.title(), msg.type(), msg.status(),
                        msg.amount(), msg.applicant(), msg.createdAt()).strip())
                .orElse("未找到 id=" + id + " 的财务消息。");
    }

    @SunshineTool(
            id = "summarize_finance_by_status",
            displayName = "统计财务消息",
            description = "按状态统计财务消息条数与金额合计。用户问有多少待审批、总额多少时使用。",
            timelineSummaryTemplate = "{status} {count} 条，合计 ¥{amount}",
            timelineSummaryExtract = "{\"status\":\"regex:status=([^|\\\\s]+)\",\"count\":\"regex:count=(\\\\d+)\",\"amount\":\"regex:totalAmount=([\\\\d.]+)\"}")
    public String summarizeFinanceByStatus(
            @ToolParam(value = "status", description = "pending | approved | all", required = false) String status) {
        List<FinanceMessageSummaryVO> summaries = messageService.summarize(status != null ? status : "all");
        if (summaries.isEmpty()) {
            return "未查询到财务汇总数据。";
        }
        StringBuilder sb = new StringBuilder("财务消息汇总：\n");
        for (FinanceMessageSummaryVO row : summaries) {
            sb.append("- status=").append(row.status())
                    .append(" | count=").append(row.count())
                    .append(" | totalAmount=").append(row.totalAmount())
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
