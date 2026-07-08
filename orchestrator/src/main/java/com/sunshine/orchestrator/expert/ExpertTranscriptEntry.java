package com.sunshine.orchestrator.expert;

public record ExpertTranscriptEntry(
        String expertId,
        String displayName,
        int speakSeq,
        String content
) {
}
