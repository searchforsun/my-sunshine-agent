package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.processing.TaskProcessRenderer;
import com.sunshine.orchestrator.processing.ToolSchemaRenderer;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 上下文组装用轮次：可选 messageId（供 Mid {@code mid_answers} 查找）；
 * assistant 轮可携带 ① 确定性工具 schema 行（五层 §5.5.8 / task-scene §6.5，Mid 骨架）
 * ② task Near 完整过程行（task-scene §6.6，think 推理全文 + tool 序列原文）。
 */
public record SessionTurn(
        String messageId,
        String role,
        String content,
        List<String> toolSchemaLines,
        List<String> processLines) {

    public SessionTurn {
        toolSchemaLines = toolSchemaLines != null && !toolSchemaLines.isEmpty()
                ? List.copyOf(toolSchemaLines) : null;
        processLines = processLines != null && !processLines.isEmpty()
                ? List.copyOf(processLines) : null;
    }

    public SessionTurn(String messageId, String role, String content, List<String> toolSchemaLines) {
        this(messageId, role, content, toolSchemaLines, null);
    }

    public static SessionTurn of(String role, String content) {
        return new SessionTurn(null, role, content, null);
    }

    public static SessionTurn of(String messageId, String role, String content) {
        return new SessionTurn(messageId, role, content, null);
    }

    public static SessionTurn of(String messageId, String role, String content, List<String> toolSchemaLines) {
        return new SessionTurn(messageId, role, content, toolSchemaLines);
    }

    /** 消息实体的统一构建：assistant 消息从 steps JSON 渲染 schema 行与（task）完整过程行，user 不附。 */
    public static SessionTurn fromMessage(String messageId, String role, String content, String stepsJson, String kind) {
        List<String> schema = null;
        List<String> process = null;
        if ("assistant".equals(role) && StringUtils.hasText(stepsJson)) {
            List<String> lines = ToolSchemaRenderer.renderSchemaLines(stepsJson);
            if (!lines.isEmpty()) {
                schema = lines;
            }
            if ("task".equals(kind)) {
                List<String> full = TaskProcessRenderer.renderProcessLines(stepsJson);
                if (!full.isEmpty()) {
                    process = full;
                }
            }
        }
        return new SessionTurn(messageId, role, content, schema, process);
    }
}
