package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.context.AssembledContext;

import java.util.List;

/**
 * PromptComposer 输入 — 6 层叠加 + 场景覆盖层的上下文载体。
 * personalRules：用户个人规则（soul），非空时作为独立注入层（Gateway 链路在 base-system 之后，ReAct 链路在 mode-overlay 之后）。
 * kind：会话类型（"chat" / "task"），用于注入场景覆盖层 scene-overlay.{kind}。
 * triggeredSkillIds：本轮已触发 skill 集（L0 / sticky / 高置信），PromptComposer 仅对其灌全文 overlay；
 * 其余可发现 skill 走目录摘要层（名+描述），正文按需加载（skill-sticky S-D/S-T）。
 */
public record PromptComposeRequest(
        PromptMode mode,
        AssembledContext context,
        String userMessage,
        String workflowId,
        String skillId,
        String nodePrompt,
        List<String> injectedUserContexts,
        String partialAssistant,
        boolean reactRestart,
        /** Planner-Executor harness overlay id（机制层；仅 kind=planner 生效，见 PromptComposer.resolveHarnessOverlay） */
        String harnessPromptId,
        String personalRules,
        String kind,
        /** 工作区 checkout 目录（kind=task 会话）；非空时注入「当前工作目录」提示 */
        String workspaceCheckout,
        /** 本轮已触发 skill 集（主 Agent；SUB/Workflow 用单数 skillId） */
        List<String> triggeredSkillIds,
        /** 本轮候选 skill 集（S-C：目录提权 + dynamicLoadable 标记，可动态加载升级） */
        List<String> candidateSkillIds,
        /** 会话租户（A-2：可发现目录按租户过滤；null 视为 default） */
        String tenantId) {

    public PromptComposeRequest {
        injectedUserContexts = injectedUserContexts != null ? List.copyOf(injectedUserContexts) : List.of();
        triggeredSkillIds = triggeredSkillIds != null ? List.copyOf(triggeredSkillIds) : List.of();
        candidateSkillIds = candidateSkillIds != null ? List.copyOf(candidateSkillIds) : List.of();
        tenantId = tenantId != null ? tenantId : "default";
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, List<String> injectedUserContexts) {
        return forReact(context, userMessage, null, injectedUserContexts, false);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId, List<String> injectedUserContexts) {
        return forReact(context, userMessage, skillId, injectedUserContexts, false);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart) {
        return forReact(context, userMessage, skillId, injectedUserContexts, reactRestart, null);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String personalRules) {
        return forReact(context, userMessage, skillId, injectedUserContexts, reactRestart, personalRules, null);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String personalRules, String kind) {
        return forReact(context, userMessage, skillId, injectedUserContexts, reactRestart, personalRules, kind, null);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String personalRules, String kind,
            String workspaceCheckout) {
        return forReact(context, userMessage, skillId, injectedUserContexts, reactRestart, personalRules, kind,
                workspaceCheckout, null);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String personalRules, String kind,
            String workspaceCheckout, List<String> triggeredSkillIds) {
        return forReact(context, userMessage, skillId, injectedUserContexts, reactRestart, personalRules, kind,
                workspaceCheckout, triggeredSkillIds, null);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String personalRules, String kind,
            String workspaceCheckout, List<String> triggeredSkillIds, String tenantId) {
        return new PromptComposeRequest(
                PromptMode.REACT, context, userMessage, null, skillId, null, injectedUserContexts, null,
                reactRestart, null, personalRules, kind, workspaceCheckout, triggeredSkillIds, null, tenantId);
    }

    /** S-C：含候选 skill 集（目录提权 + dynamicLoadable） */
    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String personalRules, String kind,
            String workspaceCheckout, List<String> triggeredSkillIds, List<String> candidateSkillIds, String tenantId) {
        return new PromptComposeRequest(
                PromptMode.REACT, context, userMessage, null, skillId, null, injectedUserContexts, null,
                reactRestart, null, personalRules, kind, workspaceCheckout, triggeredSkillIds, candidateSkillIds, tenantId);
    }

    /** Planner-Executor — Planner 独立角色（不叠 react/workflow 叠加层），仅由 harnessPromptId 提供角色定位。 */
    public static PromptComposeRequest forPlannerHarness(
            AssembledContext context, String userMessage, List<String> injectedUserContexts,
            boolean reactRestart, String harnessPromptId, String kind, String workspaceCheckout) {
        return new PromptComposeRequest(
                PromptMode.PLANNER, context, userMessage, null, null, null, injectedUserContexts, null,
                reactRestart, harnessPromptId, null, kind, workspaceCheckout, null, null, null);
    }

    /** workflow llm 节点 — nodePrompt 为 TemplateResolver 渲染后的第 6 层 */
    public static PromptComposeRequest forWorkflowLlm(
            String workflowId, AssembledContext context, String userMessage, String nodePrompt) {
        return forWorkflowLlm(workflowId, context, userMessage, nodePrompt, null);
    }

    public static PromptComposeRequest forWorkflowLlm(
            String workflowId, AssembledContext context, String userMessage, String nodePrompt, String personalRules) {
        return forWorkflowLlm(workflowId, context, userMessage, nodePrompt, personalRules, null);
    }

    public static PromptComposeRequest forWorkflowLlm(
            String workflowId, AssembledContext context, String userMessage, String nodePrompt,
            String personalRules, String kind) {
        return new PromptComposeRequest(
                PromptMode.WORKFLOW, context, userMessage, workflowId, null, nodePrompt, List.of(), null, false, null,
                personalRules, kind, null, null, null, null);
    }

}
