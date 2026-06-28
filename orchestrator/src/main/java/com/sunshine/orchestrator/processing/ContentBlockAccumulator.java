package com.sunshine.orchestrator.processing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.StreamToken;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 流式正文分段落库：消息级 content_blocks + node 步 steps.contentBlocks */
public final class ContentBlockAccumulator {

    private static final ObjectMapper OM = new ObjectMapper();

    private final List<MutableBlock> messageBlocks = new ArrayList<>();
    private final Map<String, List<MutableBlock>> nodeBlocks = new LinkedHashMap<>();
    private final Map<String, MutableBlock> openBySegmentId = new LinkedHashMap<>();

    public void onContentStart(StreamToken token) {
        if (token == null || !token.isContentStart() || !StringUtils.hasText(token.segmentId())) {
            return;
        }
        MutableBlock block = new MutableBlock(token.segmentId(), token.afterStepId());
        openBySegmentId.put(token.segmentId(), block);
        target(token.scopeNodeStepId()).add(block);
    }

    public void onContent(StreamToken token) {
        if (token == null || !token.isContent() || !StringUtils.hasText(token.segmentId())) {
            return;
        }
        MutableBlock block = openBySegmentId.get(token.segmentId());
        if (block == null) {
            block = new MutableBlock(token.segmentId(), token.afterStepId());
            openBySegmentId.put(token.segmentId(), block);
            target(token.scopeNodeStepId()).add(block);
        }
        if (StringUtils.hasText(token.text())) {
            block.text.append(token.text());
        }
    }

    public void onContentEnd(StreamToken token) {
        if (token == null || !token.isContentEnd() || !StringUtils.hasText(token.segmentId())) {
            return;
        }
        openBySegmentId.remove(token.segmentId());
    }

    public void mergeIntoSteps(List<ProcessingStep> steps) {
        for (Map.Entry<String, List<MutableBlock>> entry : nodeBlocks.entrySet()) {
            List<ContentBlock> blocks = toImmutable(entry.getValue());
            if (!blocks.isEmpty()) {
                ProcessingStepMerger.setStepContentBlocks(steps, entry.getKey(), blocks);
            }
        }
    }

    public String messageBlocksJson() {
        List<ContentBlock> blocks = toImmutable(messageBlocks);
        if (blocks.isEmpty()) {
            return null;
        }
        try {
            return OM.writeValueAsString(blocks);
        } catch (Exception e) {
            return null;
        }
    }

    private List<MutableBlock> target(String scopeNodeStepId) {
        if (StringUtils.hasText(scopeNodeStepId)) {
            return nodeBlocks.computeIfAbsent(scopeNodeStepId.strip(), k -> new ArrayList<>());
        }
        return messageBlocks;
    }

    private static List<ContentBlock> toImmutable(List<MutableBlock> blocks) {
        List<ContentBlock> out = new ArrayList<>(blocks.size());
        for (MutableBlock block : blocks) {
            String text = block.text.toString();
            if (!text.isEmpty()) {
                out.add(new ContentBlock(block.segmentId, block.afterStepId, text));
            }
        }
        return out;
    }

    private static final class MutableBlock {
        private final String segmentId;
        private final String afterStepId;
        private final StringBuilder text = new StringBuilder();

        private MutableBlock(String segmentId, String afterStepId) {
            this.segmentId = segmentId;
            this.afterStepId = afterStepId;
        }
    }
}
