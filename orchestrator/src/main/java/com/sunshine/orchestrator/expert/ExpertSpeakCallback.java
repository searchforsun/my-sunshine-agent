package com.sunshine.orchestrator.expert;

@FunctionalInterface
public interface ExpertSpeakCallback {
    void onSpeak(ExpertTranscriptEntry entry, String lifecycle, boolean responding);

    /** 专家终态正文一次性写入 expert 步 result（ReAct 工具 acting 阶段无法 token 流式） */
    default void onSpeakDelta(ExpertTranscriptEntry entry, String text) {
    }

    /** 专家内部工具调用等 — 仅刷新主行 active，不上报 tool 步 */
    default void onSpeakActive(ExpertTranscriptEntry entry, String activeText) {
    }
}
