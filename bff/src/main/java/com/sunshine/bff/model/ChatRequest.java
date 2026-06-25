package com.sunshine.bff.model;

import lombok.Data;

@Data
public class ChatRequest {

    private String content;

    private String conversationId;

    /** 续传目标 assistant 消息 id，与 content 互斥 */
    private String resumeMessageId;

    /** auto | simple-llm | react | workflow | plan-workflow */
    private String executionPreference;

    /** 强制 workflow 模式时可选指定 catalog id */
    private String workflowId;

    /** 前端解析到的 catalog skillId，L0 优先绑定 */
    private String skillId;
}
