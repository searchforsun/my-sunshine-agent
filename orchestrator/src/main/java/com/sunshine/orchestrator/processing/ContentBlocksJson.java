package com.sunshine.orchestrator.processing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息级 {@code content_blocks} JSON ↔ 纯文本。
 * L1/历史注入以 {@code content} 为 SSOT；分段 UI 存 blocks，落库时须回填 content。
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
     * 用于 L1 / 历史轮次，避免 ReAct 分段路径 content 空导致幽灵待办。
     */
    public static String resolveBody(String content, String contentBlocksJson) {
        if (StringUtils.hasText(content)) {
            return content.strip();
        }
        return flattenToPlainText(contentBlocksJson);
    }

    public static List<ContentBlock> parse(String contentBlocksJson) {
        if (!StringUtils.hasText(contentBlocksJson)) {
            return List.of();
        }
        try {
            List<ContentBlock> blocks = OM.readValue(contentBlocksJson, LIST);
            return blocks != null ? blocks : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 已有 content-N 段号最大值；无则 0 */
    public static int maxContentSegmentSeq(String contentBlocksJson) {
        int max = 0;
        for (ContentBlock block : parse(contentBlocksJson)) {
            if (block == null || block.segmentId() == null || !block.segmentId().startsWith("content-")) {
                continue;
            }
            String rest = block.segmentId().substring("content-".length());
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                rest = rest.substring(0, hash);
            }
            try {
                max = Math.max(max, Integer.parseInt(rest));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return max;
    }

    /**
     * 与前端 pruneContentBlocksForReactResume / ThinkStepIds.truncateToLastCompleteThink 对齐：
     * 只保留锚在「最后一个完整 think」及之前步骤上的正文块。
     */
    public static String pruneForReactResume(String contentBlocksJson, List<com.sunshine.orchestrator.agent.ProcessingStep> steps) {
        List<ContentBlock> blocks = parse(contentBlocksJson);
        if (blocks.isEmpty()) {
            return null;
        }
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        Map<String, Integer> stepIndex = new java.util.LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            com.sunshine.orchestrator.agent.ProcessingStep s = steps.get(i);
            if (s != null && s.id() != null) {
                stepIndex.put(s.id(), i);
            }
        }
        int anchorIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            com.sunshine.orchestrator.agent.ProcessingStep step = steps.get(i);
            if (step == null || !ThinkStepIds.isThinkStep(step.id()) || !"done".equals(step.lifecycle())) {
                continue;
            }
            boolean followedByTool = false;
            for (int j = i + 1; j < steps.size(); j++) {
                com.sunshine.orchestrator.agent.ProcessingStep next = steps.get(j);
                if (next == null) {
                    continue;
                }
                if (ThinkStepIds.isThinkStep(next.id()) || TimelineStepId.TASKS.matches(next.id())) {
                    continue;
                }
                followedByTool = true;
                break;
            }
            if (followedByTool) {
                anchorIdx = i;
            }
        }
        List<ContentBlock> kept = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block == null || block.afterStepId() == null) {
                continue;
            }
            Integer idx = stepIndex.get(block.afterStepId());
            if (idx == null) {
                continue;
            }
            if (anchorIdx < 0 || idx <= anchorIdx) {
                kept.add(block);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        try {
            return OM.writeValueAsString(kept);
        } catch (Exception e) {
            return contentBlocksJson;
        }
    }
}
