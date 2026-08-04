package com.sunshine.orchestrator.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ReAct 元工具 — 每轮推理（think）阶段结构化输出本轮摘要。
 *
 * <p>模型经工具参数输出 {@code summary}（20 字以内），{@link ProcessingStepMiddleware} 在
 * onActing 拦截后写入 think 步 step_summary，前端主行显示摘要；本工具不产生 tool-* 步、
 * 不触发 HITL，结果对模型仅作确认。摘要作为工具参数结构化输出，替代「content 首行【摘要】」裁切。
 */
@Slf4j
@Component
public class ThinkSummaryTool {

    public static final String NAME = "think_summary";

    @Tool(name = NAME, description = "在每轮推理阶段输出本轮摘要，供时间线展示。"
            + "每轮发起任何业务工具调用前，必须先调用本工具，summary 填写本轮要做的简述（20 字以内）；"
            + "最后一轮不再调用业务工具、直接作答时，summary 填写「完整回答用户问题」。")
    public String thinkSummary(
            @ToolParam(name = "summary", description = "本轮思考摘要（20 字以内）") String summary) {
        if (!StringUtils.hasText(summary)) {
            return "{\"ok\":false,\"error\":\"summary 不能为空\"}";
        }
        return "{\"ok\":true}";
    }
}
