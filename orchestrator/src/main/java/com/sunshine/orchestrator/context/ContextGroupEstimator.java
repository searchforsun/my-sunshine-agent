package com.sunshine.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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
                total += estimateContentBlocks(m.getContent());
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

    /** 枚举一条消息的所有 ContentBlock 分别估算（getTextContent 只算 text，会漏掉 tool_use / tool_result 等） */
    private int estimateContentBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ContentBlock b : blocks) {
            if (b == null) {
                continue;
            }
            if (b instanceof TextBlock t) {
                total += tokenEstimator.count(t.getText());
            } else if (b instanceof ThinkingBlock t) {
                total += tokenEstimator.count(t.getThinking());
            } else if (b instanceof HintBlock t) {
                total += tokenEstimator.count(t.getHint());
            } else if (b instanceof ToolUseBlock t) {
                total += tokenEstimator.count(t.getName());
                total += tokenEstimator.count(writeJson(t.getInput()));
            } else if (b instanceof ToolResultBlock t) {
                total += tokenEstimator.count(t.getName());
                total += estimateContentBlocks(t.getOutput());
            }
            // Image/Audio/Video/DataBlock：与 token 估算无关，跳过
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
