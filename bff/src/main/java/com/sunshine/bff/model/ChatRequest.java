package com.sunshine.bff.model;

import lombok.Data;

@Data
public class ChatRequest {

    private String content;

    private String conversationId;

    /** 续传目标 assistant 消息 id，与 content 互斥 */
    private String resumeMessageId;

    /** 执行模式（v6）：fast | pro | workflow；缺省回落 executionPreference */
    private String executionMode;

    /** @deprecated 兼容旧 wire；优先 executionMode */
    private String executionPreference;

    /** 强制 workflow 模式时可选指定 catalog id */
    private String workflowId;

    /** 前端解析到的 catalog skillId，L0 优先绑定 */
    private String skillId;

    /** 会话绑定的知识库 id；空则 orchestrator 解析租户默认库 */
    private String kbId;

    /** never | always | smart — 沙箱写操作 HITL 跳过；缺省 never */
    private String writeHitlMode;

    /** 用户个人规则（soul）；透传字段，BFF 不加工 */
    private String personalRules;

    /** 会话级模型覆盖（可选）；透传 orchestrator，BFF 不加工 */
    private String modelName;
}
