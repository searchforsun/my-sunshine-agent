package com.sunshine.orchestrator.peer;

/** MsgHub 单条发言记录 */
public record PeerTranscriptEntry(
        int round,
        String roleName,
        String skillId,
        String content) {
}
