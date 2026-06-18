package com.sunshine.finance.service;

import com.sunshine.finance.dto.FinanceMessageSummaryVO;
import com.sunshine.finance.dto.FinanceMessageVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class FinanceMessageService {

    private static final List<FinanceMessageVO> MOCK = List.of(
            new FinanceMessageVO(1001, "Q2 差旅报销审批", "reimbursement", "pending",
                    new BigDecimal("3280.50"), "张三", "2026-06-14 09:30"),
            new FinanceMessageVO(1002, "办公用品采购付款", "payment", "pending",
                    new BigDecimal("860.00"), "李四", "2026-06-15 11:05"),
            new FinanceMessageVO(1003, "6 月供应商对账", "reconciliation", "approved",
                    new BigDecimal("45200.00"), "王五", "2026-06-10 16:20"),
            new FinanceMessageVO(1004, "项目预算调整申请", "budget", "pending",
                    new BigDecimal("120000.00"), "赵六", "2026-06-16 08:45"),
            new FinanceMessageVO(1005, "客户退款处理", "refund", "approved",
                    new BigDecimal("1999.00"), "陈七", "2026-06-12 14:00")
    );

    public List<FinanceMessageVO> list(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return MOCK;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        List<FinanceMessageVO> out = new ArrayList<>();
        for (FinanceMessageVO msg : MOCK) {
            if (normalized.equals(msg.status())) {
                out.add(msg);
            }
        }
        return out;
    }

    public Optional<FinanceMessageVO> getById(long id) {
        for (FinanceMessageVO msg : MOCK) {
            if (msg.id() == id) {
                return Optional.of(msg);
            }
        }
        return Optional.empty();
    }

    /** 按状态汇总条数与金额；status 为空时返回 pending / approved 两组 */
    public List<FinanceMessageSummaryVO> summarize(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            List<FinanceMessageSummaryVO> summaries = new ArrayList<>();
            summaries.add(buildSummary("pending"));
            summaries.add(buildSummary("approved"));
            return summaries;
        }
        return List.of(buildSummary(status.trim().toLowerCase(Locale.ROOT)));
    }

    private FinanceMessageSummaryVO buildSummary(String status) {
        List<FinanceMessageVO> rows = list(status);
        BigDecimal total = BigDecimal.ZERO;
        for (FinanceMessageVO row : rows) {
            total = total.add(row.amount());
        }
        return new FinanceMessageSummaryVO(status, rows.size(), total);
    }
}
