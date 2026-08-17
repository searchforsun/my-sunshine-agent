package com.sunshine.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文分组构成展示估算（仅展示，标 ~）：总额/裁剪仍以网关真实 usage 为准。
 * 工具 schema 以 JSON 序列化文本估算，与上游分词偏差归入「其他」残差组。
 */
@Component
@RequiredArgsConstructor
public class ContextGroupEstimator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TokenEstimator tokenEstimator;

    public int estimateText(String text) {
        return tokenEstimator.count(text);
    }

    public int estimateMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Msg m : messages) {
            if (m != null) {
                total += tokenEstimator.count(m.getTextContent());
            }
        }
        return total;
    }

    public int estimateTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ToolSchema t : tools) {
            if (t == null) {
                continue;
            }
            total += tokenEstimator.count(t.getName());
            total += tokenEstimator.count(t.getDescription());
            total += tokenEstimator.count(writeJson(t.getParameters()));
        }
        return total;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
