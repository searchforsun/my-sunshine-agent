package com.sunshine.orchestrator.expert;

@FunctionalInterface
public interface ExpertSpeakCallback {
    void onSpeak(ExpertTranscriptEntry entry, String lifecycle, boolean responding);

    /** 专家发言正文增量 token — 阶段2 Gateway 直链写入 expert 步 result */
    default void onSpeakDelta(ExpertTranscriptEntry entry, String text) {
    }

    /** 专家内部工具调用等 — 仅刷新主行 active，不上报 tool 步 */
    default void onSpeakActive(ExpertTranscriptEntry entry, String activeText) {
    }
}
