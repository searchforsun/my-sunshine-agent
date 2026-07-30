package com.sunshine.orchestrator.model;

import lombok.Data;

@Data
public class ChatMessage {

    private String content;

    private String conversationId;

    /** 续传目标 assistant 消息 id，与 content 互斥 */
    private String resumeMessageId;

    /** auto | react | workflow | plan-workflow */
    private String executionPreference;

    /** 强制 workflow 模式时可选指定 catalog id */
    private String workflowId;

    /** 前端解析到的 catalog skillId，L0 优先绑定 */
    private String skillId;

    /** 会话绑定的知识库 id；空则 orchestrator 解析租户默认库 */
    private String kbId;

    /** never | always | smart — 沙箱写操作 HITL 跳过；缺省 never */
    private String writeHitlMode;

    /** 用户个人规则（soul）；空则不注入，>4000 由 orchestrator 防御截断 */
    private String personalRules;
}
