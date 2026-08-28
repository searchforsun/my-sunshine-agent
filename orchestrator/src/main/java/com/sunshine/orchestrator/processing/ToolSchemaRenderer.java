package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.sandbox.SandboxIds;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具轮确定性 schema 行渲染（五层 §5.5.8 / task-scene §6.5）：从 {@code chat_message.steps}
 * JSON 提取工具步，逐条渲染为固定格式行——零 LLM、可预期。
 * <pre>
 * [toolName] keyArgs=… status=ok|fail|denied exit=? · result≤200 · refs=[path:line]
 * </pre>
 * 写/改类工具不携带 result（禁止 patch 原文进 Mid）；沙箱路径进 refs。
 */
public final class ToolSchemaRenderer {

    private static final int RESULT_MAX_LEN = 200;

    private ToolSchemaRenderer() {
    }

    /** 从持久化 steps JSON 渲染该消息的 schema 行列表（无工具轮 / 解析失败返回空列表） */
    public static List<String> renderSchemaLines(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        List<ProcessingStep> steps = ProcessingStepSerde.fromJson(stepsJson);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(steps.size());
        for (ProcessingStep step : steps) {
            String line = renderSchemaLine(step);
            if (line != null) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    /** 单工具步 → schema 行；非工具步 / 未完成步 / 无有效字段返回 null */
    public static String renderSchemaLine(ProcessingStep step) {
        return renderLine(step, false);
    }

    /** task Near 完整过程行（§6.6）：与 schema 行同构，但写/改类保留输出原文（完整 patch/代码）；读/执行类仍 ≤200 + refs */
    public static String renderProcessLine(ProcessingStep step) {
        return renderLine(step, true);
    }

    private static String renderLine(ProcessingStep step, boolean keepWriteResult) {
        if (step == null || step.id() == null) {
            return null;
        }
        String baseId = ToolStepIds.stripInvokeSuffix(step.id());
        if (!ToolStepIds.isToolStep(baseId)) {
            return null;
        }
        String lifecycle = step.lifecycle();
        String status = mapStatus(lifecycle);
        if (status == null) {
            // running/pending 未完成，不进确定性行
            return null;
        }
        String toolName = ToolStepIds.catalogToolName(baseId);
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(toolName).append(']');
        StepMetadata metadata = step.metadata();
        String keyArgs = metadata != null ? metadata.toolArgs() : null;
        if (StringUtils.hasText(keyArgs)) {
            sb.append(" keyArgs=").append(keyArgs.strip());
        }
        sb.append(" status=").append(status);
        Integer exitCode = metadata != null ? metadata.toolExitCode() : null;
        if (exitCode != null) {
            sb.append(" exit=").append(exitCode);
        }
        // Near 完整过程：写/改类保留原文（patch/代码），其余机械截断 ≤200 chars；
        // Mid schema 行：写/改类不带 result（原文/补丁不进 Mid）
        if (!isWriteEdit(toolName) || keepWriteResult) {
            if (StringUtils.hasText(step.detail())) {
                sb.append(" · result=")
                        .append(keepWriteResult && isWriteEdit(toolName)
                                ? step.detail().strip()
                                : truncate(step.detail().strip()));
            }
        }
        String refs = refsOf(toolName, metadata);
        if (StringUtils.hasText(refs)) {
            sb.append(" · refs=[").append(refs).append(']');
        }
        if (sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    /** lifecycle → schema 状态；未完成步返回 null */
    private static String mapStatus(String lifecycle) {
        if (lifecycle == null) {
            return null;
        }
        return switch (lifecycle) {
            case "done" -> "ok";
            case "error", "terminated" -> "fail";
            case "paused", "skipped" -> "denied";
            default -> null;
        };
    }

    private static boolean isWriteEdit(String toolName) {
        return SandboxIds.WRITE.equals(toolName) || SandboxIds.EDIT.equals(toolName);
    }

    /** refs 白名单：沙箱 read/write/edit 的容器内路径（path:line 由前端从 summary 解析时留空行号） */
    private static String refsOf(String toolName, StepMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.sandboxPath())) {
            return null;
        }
        return metadata.sandboxPath().strip();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= RESULT_MAX_LEN) {
            return text;
        }
        return text.substring(0, RESULT_MAX_LEN) + "…";
    }
}
