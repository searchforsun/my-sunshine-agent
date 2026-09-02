package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * task Near 完整过程装载（task-scene §6.6）：从 {@code chat_message.steps} 渲染
 * 「think 推理全文 + tool 序列」确定性过程行——零 LLM、可预期。写/改类工具保留
 * 输出原文（完整 patch / 代码，Near 短期保留）；读/执行类结果 ≤200 + refs。
 * 与 {@link ToolSchemaRenderer}（Mid 骨架 schema 行）同源渲染规则，仅 result 分级不同。
 */
public final class TaskProcessRenderer {

    private TaskProcessRenderer() {
    }

    /** 从持久化 steps JSON 渲染该消息的完整过程行列表（无工具/推理或解析失败返回空列表） */
    public static List<String> renderProcessLines(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        List<ProcessingStep> steps = ProcessingStepSerde.fromJson(stepsJson);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(steps.size());
        for (ProcessingStep step : steps) {
            if (step == null || step.id() == null) {
                continue;
            }
            if (ThinkStepIds.isThinkStep(step.id())) {
                // think 推理全文保留（§6.6：think: 推理全文）
                if (StringUtils.hasText(step.reasoning())) {
                    lines.add("think: " + step.reasoning().strip());
                }
            } else {
                String toolLine = ToolSchemaRenderer.renderProcessLine(step);
                if (toolLine != null) {
                    lines.add(toolLine);
                }
            }
        }
        return List.copyOf(lines);
    }
}
