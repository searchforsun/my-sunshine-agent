package com.sunshine.orchestrator.conversation;

/**
 * chat_message.status 状态机
 */
public final class MessageStatus {

    public static final String STREAMING = "streaming";
    public static final String INTERRUPTED = "interrupted";
    public static final String FAILED = "failed";
    public static final String COMPLETED = "completed";

    private MessageStatus() {
    }

    public static boolean isResumable(String status) {
        return INTERRUPTED.equals(status) || FAILED.equals(status);
    }
}
