package com.sunshine.bff.model;

import lombok.Data;

@Data
public class ChatRequest {

    private String content;

    private String conversationId;

    /** 续传目标 assistant 消息 id，与 content 互斥 */
    private String resumeMessageId;
}
