package com.sunshine.orchestrator.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** orchestrator 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum OrchestratorErrorCode implements ErrorCode {

    CONVERSATION_NOT_FOUND(404, "orch_conversation_not_found", "会话不存在"),
    MESSAGE_NOT_FOUND(404, "orch_message_not_found", "消息不存在"),
    GENERATION_NOT_FOUND(404, "orch_generation_not_found", "生成任务不存在"),
    EXECUTION_PLAN_NOT_FOUND(404, "orch_execution_plan_not_found", "执行计划不存在"),
    RESUME_NOT_ALLOWED(409, "orch_resume_not_allowed", "当前消息不可续传"),
    GENERATION_STOPPED(410, "orch_generation_stopped", "生成任务已停止"),
    GENERATION_IN_PROGRESS(409, "orch_generation_in_progress", "消息正在生成中"),
    INVALID_CHAT_REQUEST(400, "orch_invalid_chat_request", "请求参数有误"),
    HITL_DISABLED(503, "orch_hitl_disabled", "人工确认功能未启用"),
    CONFIRM_TOKEN_REQUIRED(400, "orch_confirm_token_required", "确认令牌不能为空"),
    EXECUTION_PLAN_STATE_INVALID(409, "orch_execution_plan_state_invalid", "执行计划状态异常"),
    EXECUTION_PLAN_PERSIST_INCOMPLETE(500, "orch_execution_plan_persist_incomplete", "执行计划数据不完整"),
    WORKFLOW_TEMPLATE_NOT_FOUND(400, "orch_workflow_template_not_found", "未匹配到可用的工作流模板"),
    SKILL_NOT_FOUND(400, "orch_skill_not_found", "未找到指定的 Skill，请检查列表后重试");

    private final int code;
    private final String key;
    private final String message;
}
