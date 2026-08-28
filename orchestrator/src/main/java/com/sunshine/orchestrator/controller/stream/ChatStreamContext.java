package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.RoutingSeed;

/** 单次 chat/stream 请求的会话与执行上下文（新消息或续跑） */
public record ChatStreamContext(
        String conversationId,
        String assistantMsgId,
        String conversationTitle,
        String userContent,
        AssembledContext memory,
        String existingContent,
        String existingReasoning,
        String intent,
        String existingStepsJson,
        boolean autoTitle,
        String userId,
        String tenantId,
        ExecutionMode executionPreference,
        String forcedWorkflowId,
        String clientSkillId,
        String kbId,
        boolean reactRestart,
        /** 用户个人规则（soul）；新消息路径透传，续跑路径为 null 不重注入 */
        String personalRules,
        /** 会话类型：chat / task（task 会话使用更高的 ReAct 轮数上限） */
        String conversationKind,
        /** 会话绑定模型 override（MAIN chat）；intent/rewrite/title 忽略 */
        String modelOverride,
        /**
         * RoutingResult seed（skill-sticky S-0/S-1）：
         * 新消息 = 上轮轻 sticky seed（无新触发时继承）；续跑 = 该消息已存 triggered/可调度集（直接复用）。
         */
        RoutingSeed routingSeed) {
}
