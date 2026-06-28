package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.client.StreamToken;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ReAct 正文分段：每轮 think 完成后开一段，工具调用前关闭。
 * 段内只接受相对 {@link #segmentBaseline} 的单调累积前缀扩展，禁止终态快照二次灌入。
 */
public final class ContentSegmentCoordinator {

    private int seq;
    private String openSegmentId;
    private String openAfterStepId;
    private String segmentBaseline = "";

    /** 将分段 token 写入 session 辅助队列，由 {@link ProcessingTimelineSupport} 一并刷出 */
    public List<StreamToken> ingest(String cumulativeText, String afterStepId, Consumer<StreamToken> sink) {
        List<StreamToken> out = new ArrayList<>();
        if (cumulativeText == null || cumulativeText.isEmpty() || afterStepId == null || afterStepId.isBlank()) {
            return out;
        }
        if (openSegmentId == null || !afterStepId.equals(openAfterStepId)) {
            closeIfOpen(out, sink);
            openSegmentId = "content-" + (++seq);
            openAfterStepId = afterStepId;
            segmentBaseline = "";
            StreamToken start = StreamToken.contentStart(openSegmentId, afterStepId);
            out.add(start);
            sink.accept(start);
        }
        String delta = resolveDelta(segmentBaseline, cumulativeText);
        if (delta.isEmpty()) {
            return out;
        }
        segmentBaseline = advanceBaseline(segmentBaseline, cumulativeText, delta);
        StreamToken chunk = StreamToken.contentInSegment(openSegmentId, delta);
        out.add(chunk);
        sink.accept(chunk);
        return out;
    }

    /**
     * 区分 AgentScope 两种 REASONING 帧：
     * <ul>
     *   <li>累积帧（{@code incoming.startsWith(baseline)}）：单调前缀 diff</li>
     *   <li>增量帧（{@code incremental=true} 的 ReasoningChunk）：每帧纯正文片段，直接 append</li>
     * </ul>
     */
    static String resolveDelta(String baseline, String incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        if (baseline.isEmpty()) {
            return incoming;
        }
        if (incoming.startsWith(baseline)) {
            return monotonicDelta(baseline, incoming);
        }
        // 非前缀帧：文档头复读（incoming 已是 baseline 开头）则丢弃
        if (baseline.startsWith(incoming)) {
            return "";
        }
        return incoming;
    }

    static String advanceBaseline(String baseline, String incoming, String delta) {
        if (baseline.isEmpty()) {
            return incoming;
        }
        if (incoming.startsWith(baseline)) {
            return incoming;
        }
        return baseline + delta;
    }

    /** 当前段已下发正文累积，供 AGENT_RESULT 终态去重 */
    public String currentBaseline() {
        return segmentBaseline;
    }

    public void closeIfOpen(List<StreamToken> out, Consumer<StreamToken> sink) {
        if (openSegmentId == null) {
            return;
        }
        StreamToken end = StreamToken.contentEnd(openSegmentId);
        out.add(end);
        sink.accept(end);
        openSegmentId = null;
        openAfterStepId = null;
        segmentBaseline = "";
    }

    /** 段内仅允许 incoming 以 baseline 为前缀续写；从文档头复读或整段翻倍则丢弃 */
    static String monotonicDelta(String baseline, String incoming) {
        if (baseline.isEmpty()) {
            return incoming;
        }
        if (!incoming.startsWith(baseline)) {
            return "";
        }
        if (incoming.length() <= baseline.length()) {
            return "";
        }
        String delta = incoming.substring(baseline.length());
        if (delta.equals(baseline)) {
            return "";
        }
        // 模型终态帧常 append 整篇重述：delta 从 baseline 头部复读
        if (delta.length() >= 8 && baseline.startsWith(delta)) {
            return "";
        }
        return delta;
    }
}
