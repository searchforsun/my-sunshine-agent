package com.sunshine.orchestrator.client;

import java.util.List;
import java.util.stream.Collectors;

/** 将 RAG 检索结果格式化为 Agent 工具返回或预检索上下文 */
public final class RagContextFormatter {

    private RagContextFormatter() {
    }

    public static String formatToolResult(List<RagClient.RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return """
                    未找到相关知识库内容。
                    请直接告知用户：知识库中未找到相关规定，不要编造制度名称或条款。
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索结果（共 ").append(hits.size()).append(" 条）：\n");
        appendHitsBody(sb, hits);
        sb.append("""
                ## 引用规则（必须严格遵守）
                1. 回答中引用的文档名称只能来自上方「允许引用的文档名称」列表。
                2. 禁止引用、编造或推测列表以外的任何制度/手册/办法名称。
                3. 回答内容只能基于上述片段，不得用训练数据中的制度条款替代或补充。
                4. 若片段不足以完整回答，请明确说明「知识库中未找到更详细的规定」。
                """);
        return sb.toString();
    }

    /**
     * 预检索注入 Agent 的用户侧上下文（与用户问题分开发送，避免被模型忽略）
     */
    public static String formatAgentContext(List<RagClient.RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return """
                    [知识库预检索结果]
                    未找到与用户问题直接相关的知识库片段。

                    请明确告知用户：知识库中未找到相关规定。
                    禁止引用、编造任何制度/手册/办法名称（如《员工出勤管理办法》等）。
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[知识库预检索结果]\n");
        sb.append("知识库已预检索完成，请勿重复调用 search_knowledge，直接基于下列片段作答。\n\n");
        appendHitsBody(sb, hits);
        sb.append("""
                ## 引用规则（必须严格遵守）
                1. 回答中引用的文档名称只能来自上方「允许引用的文档名称」列表。
                2. 禁止引用、编造或推测列表以外的任何制度/手册/办法名称。
                3. 回答内容只能基于上述片段，不得用训练数据中的制度条款替代或补充。
                4. 若片段不足以完整回答，请明确说明「知识库中未找到更详细的规定」。
                """);
        return sb.toString();
    }

    private static void appendHitsBody(StringBuilder sb, List<RagClient.RagHit> hits) {
        String docNames = hits.stream()
                .map(RagClient.RagHit::docName)
                .distinct()
                .collect(Collectors.joining("、"));
        sb.append("来源文档：").append(docNames).append("\n");
        sb.append("允许引用的文档名称（仅限以下名称，不得增减或替换）：").append(docNames).append("\n\n");
        for (int i = 0; i < hits.size(); i++) {
            RagClient.RagHit hit = hits.get(i);
            sb.append("【").append(hit.docName()).append(" | 片段 ").append(i + 1).append("】\n");
            sb.append(hit.content()).append("\n\n");
        }
    }
}
