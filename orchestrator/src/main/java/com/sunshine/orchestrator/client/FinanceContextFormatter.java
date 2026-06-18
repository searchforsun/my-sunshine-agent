package com.sunshine.orchestrator.client;

/** 将财务工具返回格式化为 Agent / Workflow 上下文块 */
public final class FinanceContextFormatter {

    private FinanceContextFormatter() {
    }

    /**
     * @param toolResultJson tool-manager 返回的原始 JSON
     * @param status         本次查询使用的 status 参数
     */
    public static String formatAgentContext(String toolResultJson, String status) {
        String normalizedStatus = status == null || status.isBlank() ? "pending" : status.trim();
        if (toolResultJson == null || toolResultJson.isBlank()) {
            return """
                    [财务数据]
                    工具：list_finance_messages(status=%s)
                    结果：未返回数据
                    """.formatted(normalizedStatus);
        }

        return """
                [财务数据]
                工具：list_finance_messages(status=%s)

                %s
                """.formatted(normalizedStatus, toolResultJson.strip());
    }
}
