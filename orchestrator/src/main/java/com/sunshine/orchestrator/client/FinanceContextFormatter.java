package com.sunshine.orchestrator.client;

/** 将财务工具返回格式化为 Agent 预查询上下文（与用户问题分开发送） */
public final class FinanceContextFormatter {

    private FinanceContextFormatter() {
    }

    /**
     * @param toolResultJson tool-manager 返回的原始 JSON
     * @param status         本次预查询使用的 status 参数
     */
    public static String formatAgentContext(String toolResultJson, String status) {
        String normalizedStatus = status == null || status.isBlank() ? "pending" : status.trim();
        if (toolResultJson == null || toolResultJson.isBlank()) {
            return """
                    [财务工具预查询结果]
                    工具：list_finance_messages(status=%s)
                    结果：未返回数据。

                    已预查询完成，请勿重复调用 list_finance_messages。
                    """.formatted(normalizedStatus);
        }

        return """
                [财务工具预查询结果]
                工具：list_finance_messages(status=%s)
                已预查询完成，请勿重复调用 list_finance_messages。

                ## 工具返回数据
                %s
                """.formatted(normalizedStatus, toolResultJson.strip());
    }
}
