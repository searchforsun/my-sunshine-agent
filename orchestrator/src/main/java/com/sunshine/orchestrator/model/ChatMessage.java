package com.sunshine.orchestrator.model;

import lombok.Data;

@Data
public class ChatMessage {

    private String content;

    private String conversationId;

    /** 续传目标 assistant 消息 id，与 content 互斥 */
    private String resumeMessageId;

    /** 执行模式（v6 wire）：fast | pro | workflow；缺省 fast */
    private String executionMode;

    /** workflow 模式时可选指定 catalog id */
    private String workflowId;

    /** 解析用户钉死模式：executionMode，缺省 fast */
    public String resolveExecutionModeWire() {
        if (executionMode != null && !executionMode.isBlank()) {
            return executionMode.strip();
        }
        return "fast";
    }

    /** 前端解析到的 catalog skillId，L0 优先绑定 */
    private String skillId;

    /** 会话绑定的知识库 id；空则 orchestrator 解析租户默认库 */
    private String kbId;

    /** never | always | smart — 沙箱写操作 HITL 跳过；缺省 never */
    private String writeHitlMode;

    /** 用户个人规则（soul）；空则不注入，>4000 由 orchestrator 防御截断 */
    private String personalRules;

    /** 会话绑定模型（注册表 model_name）；空则走 chat/default scene */
    private String modelName;
}
