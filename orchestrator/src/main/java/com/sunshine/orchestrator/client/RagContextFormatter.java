package com.sunshine.orchestrator.client;

import java.util.List;
import java.util.stream.Collectors;

/** 将 RAG 检索结果格式化为工具返回或 Workflow 上下文 */
public final class RagContextFormatter {

    private RagContextFormatter() {
    }

    public static String formatToolResult(List<RagClient.RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "未找到相关知识库内容。请如实告知用户，勿编造制度名称或条款。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索结果（共 ").append(hits.size()).append(" 条）：\n");
        appendHitsBody(sb, hits);
        sb.append("引用文档名称须来自上方列表，内容须基于上述片段。");
        return sb.toString();
    }

    /** Workflow / 预检索上下文 */
    public static String formatAgentContext(List<RagClient.RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return """
                    [知识库检索结果]
                    未找到与用户问题直接相关的片段。
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[知识库检索结果]\n");
        appendHitsBody(sb, hits);
        return sb.toString().strip();
    }

    private static void appendHitsBody(StringBuilder sb, List<RagClient.RagHit> hits) {
        String docNames = hits.stream()
                .map(RagClient.RagHit::docName)
                .distinct()
                .collect(Collectors.joining("、"));
        sb.append("来源文档：").append(docNames).append("\n\n");
        for (int i = 0; i < hits.size(); i++) {
            RagClient.RagHit hit = hits.get(i);
            sb.append("【").append(hit.docName()).append(" | 片段 ").append(i + 1).append("】\n");
            sb.append(hit.content()).append("\n\n");
        }
    }
}
