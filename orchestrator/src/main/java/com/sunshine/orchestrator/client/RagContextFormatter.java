package com.sunshine.orchestrator.client;

import java.util.List;
import java.util.stream.Collectors;

/** 将 RAG 检索结果格式化为工具返回或 Workflow 上下文 */
public final class RagContextFormatter {

    public enum Mode { TOOL, WORKFLOW }

    private RagContextFormatter() {
    }

    public static String formatHits(List<RagClient.RagHit> hits, Mode mode) {
        if (hits == null || hits.isEmpty()) {
            return mode == Mode.TOOL
                    ? "未找到相关知识库内容。请如实告知用户，勿编造制度名称或条款。"
                    : "[知识库检索结果]\n未找到与用户问题直接相关的片段。";
        }
        StringBuilder sb = new StringBuilder();
        if (mode == Mode.TOOL) {
            sb.append("知识库检索结果（共 ").append(hits.size()).append(" 条）：\n");
        } else {
            sb.append("[知识库检索结果]\n");
        }
        appendHitsBody(sb, hits);
        if (mode == Mode.TOOL) {
            sb.append("引用文档名称须来自上方列表，内容须基于上述片段。");
        }
        return sb.toString().strip();
    }

    public static String formatToolResult(List<RagClient.RagHit> hits) {
        return formatHits(hits, Mode.TOOL);
    }

    /** Workflow / 预检索上下文 */
    public static String formatAgentContext(List<RagClient.RagHit> hits) {
        return formatHits(hits, Mode.WORKFLOW);
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
