package com.sunshine.finance.tools;

import com.sunshine.finance.dto.ExpenseSummaryVO;
import com.sunshine.finance.model.ExpenseRecord;
import com.sunshine.finance.model.FinanceInboxItem;
import com.sunshine.finance.service.FinanceBizService;
import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import com.sunshine.tools.sdk.context.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FinanceSunshineTools {

    private final FinanceBizService financeBizService;

    @SunshineTool(
            id = "list_my_expenses",
            displayName = "查询我的报销单",
            description = "查询当前用户的报销单。用户问我的报销、待报销、费用单据时调用。status: pending|approved|all",
            timelineSummaryTemplate = "{count} 条报销单",
            timelineSummaryExtract = "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}")
    public String listMyExpenses(
            @ToolParam(value = "status", description = "pending | approved | all", required = false) String status) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        List<ExpenseRecord> expenses = financeBizService.listExpenses(tenantId, userId, status != null ? status : "all");
        if (expenses.isEmpty()) {
            return "未查询到符合条件的报销单。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(expenses.size()).append(" 条报销单：\n");
        for (ExpenseRecord e : expenses) {
            sb.append("- [").append(e.id()).append("] ")
                    .append(e.category())
                    .append(" | 状态=").append(e.status())
                    .append(" | 金额=").append(e.amount())
                    .append(" | 发生日=").append(e.occurredOn());
            if (StringUtils.hasText(e.remark())) {
                sb.append(" | 备注=").append(e.remark());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    @SunshineTool(
            id = "get_expense_detail",
            displayName = "查询报销单详情",
            description = "按 expenseId 查询当前用户名下报销单详情。",
            timelineSummaryTemplate = "{category}",
            timelineSummaryExtract = "{\"category\":\"regex:-\\\\s*类别=(.+)\"}")
    public String getExpenseDetail(
            @ToolParam(value = "expenseId", description = "报销单 id，如 exp-a1") String expenseId) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        if (!StringUtils.hasText(expenseId)) {
            return "请提供报销单 expenseId。";
        }
        return financeBizService.findExpense(tenantId, userId, expenseId.trim())
                .map(e -> """
                        报销单详情：
                        - id=%s
                        - 类别=%s
                        - 状态=%s
                        - 金额=%s
                        - 发生日=%s
                        - 备注=%s
                        """.formatted(
                        e.id(), e.category(), e.status(), e.amount(),
                        e.occurredOn(), e.remark() != null ? e.remark() : "").strip())
                .orElse("未找到 expenseId=" + expenseId + " 的报销单。");
    }

    @SunshineTool(
            id = "submit_expense",
            displayName = "提交报销单",
            description = "为当前用户提交一笔报销（pending）。参数：category、amount、occurredOn、remark?",
            sideEffect = "write",
            timelineSummaryTemplate = "已提交 {expenseId}",
            timelineSummaryExtract = "{\"expenseId\":\"regex:id=(\\\\S+)\"}")
    public String submitExpense(
            @ToolParam(value = "category", description = "费用类别，如市内交通") String category,
            @ToolParam(value = "amount", description = "金额，如 86.5") String amount,
            @ToolParam(value = "occurredOn", description = "发生日 YYYY-MM-DD") String occurredOn,
            @ToolParam(value = "remark", description = "备注", required = false) String remark) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        if (!StringUtils.hasText(category) || !StringUtils.hasText(amount) || !StringUtils.hasText(occurredOn)) {
            return "请提供 category、amount、occurredOn。";
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            return "金额格式无效：" + amount;
        }
        ExpenseRecord created = financeBizService.submitExpense(
                tenantId, userId, category.trim(), parsed, occurredOn.trim(), remark);
        return "已提交报销单：id=" + created.id()
                + " | 类别=" + created.category()
                + " | 金额=" + created.amount()
                + " | 状态=" + created.status();
    }

    @SunshineTool(
            id = "list_my_finance_inbox",
            displayName = "查询我的财务待办",
            description = "查询当前用户财务收件箱待办。status: pending|approved|all",
            timelineSummaryTemplate = "{count} 条财务待办",
            timelineSummaryExtract = "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}")
    public String listMyFinanceInbox(
            @ToolParam(value = "status", description = "pending | approved | all", required = false) String status) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        List<FinanceInboxItem> items = financeBizService.listInbox(tenantId, userId, status != null ? status : "all");
        if (items.isEmpty()) {
            return "未查询到符合条件的财务待办。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(items.size()).append(" 条财务待办：\n");
        for (FinanceInboxItem item : items) {
            sb.append("- [").append(item.id()).append("] ")
                    .append(item.title())
                    .append(" | 状态=").append(item.status())
                    .append(" | 金额=").append(item.amount())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @SunshineTool(
            id = "get_finance_inbox_item",
            displayName = "查询财务待办详情",
            description = "按 itemId 查询当前用户财务收件箱条目。",
            timelineSummaryTemplate = "{title}",
            timelineSummaryExtract = "{\"title\":\"regex:-\\\\s*标题=(.+)\"}")
    public String getFinanceInboxItem(
            @ToolParam(value = "itemId", description = "待办 id，如 inbox-a1") String itemId) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        if (!StringUtils.hasText(itemId)) {
            return "请提供财务待办 itemId。";
        }
        return financeBizService.findInboxItem(tenantId, userId, itemId.trim())
                .map(item -> """
                        财务待办详情：
                        - id=%s
                        - 标题=%s
                        - 状态=%s
                        - 金额=%s
                        """.formatted(item.id(), item.title(), item.status(), item.amount()).strip())
                .orElse("未找到 itemId=" + itemId + " 的财务待办。");
    }

    @SunshineTool(
            id = "summarize_my_expenses",
            displayName = "汇总我的报销",
            description = "按状态统计当前用户报销单条数与金额合计。",
            timelineSummaryTemplate = "{status} {count} 条，合计 ¥{amount}",
            timelineSummaryExtract = "{\"status\":\"regex:status=([^|\\\\s]+)\",\"count\":\"regex:count=(\\\\d+)\",\"amount\":\"regex:totalAmount=([\\\\d.]+)\"}")
    public String summarizeMyExpenses(
            @ToolParam(value = "status", description = "pending | approved | all", required = false) String status) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        List<ExpenseSummaryVO> summaries = financeBizService.summarizeExpenses(tenantId, userId, status != null ? status : "all");
        if (summaries.isEmpty()) {
            return "未查询到报销汇总数据。";
        }
        StringBuilder sb = new StringBuilder("报销汇总：\n");
        for (ExpenseSummaryVO row : summaries) {
            sb.append("- status=").append(row.status())
                    .append(" | count=").append(row.count())
                    .append(" | totalAmount=").append(row.totalAmount())
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
