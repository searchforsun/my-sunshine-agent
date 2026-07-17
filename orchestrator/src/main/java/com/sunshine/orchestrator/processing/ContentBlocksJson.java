package com.sunshine.orchestrator.processing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息级 {@code content_blocks} JSON ↔ 纯文本。
 * STM / MTM / 历史注入以 {@code content} 为 SSOT；分段 UI 存 blocks，落库时须回填 content。
 */
public final class ContentBlocksJson {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<List<ContentBlock>> LIST = new TypeReference<>() {};

    private ContentBlocksJson() {
    }

    /** 按 segment 顺序拼接正文；块间空行分隔（与 UI 多段气泡一致） */
    public static String flattenToPlainText(String contentBlocksJson) {
        if (!StringUtils.hasText(contentBlocksJson)) {
            return "";
        }
        List<ContentBlock> blocks;
        try {
            blocks = OM.readValue(contentBlocksJson, LIST);
        } catch (Exception e) {
            return "";
        }
        return joinTexts(blocks);
    }

    public static String joinTexts(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block != null && StringUtils.hasText(block.text())) {
                parts.add(block.text().strip());
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 优先 {@code content}；为空则从 {@code content_blocks} 回填。
     * 用于 STM / 历史轮次，避免 ReAct 分段路径 content 空导致幽灵待办。
     */
    public static String resolveBody(String content, String contentBlocksJson) {
        if (StringUtils.hasText(content)) {
            return content.strip();
        }
        return flattenToPlainText(contentBlocksJson);
    }
}
