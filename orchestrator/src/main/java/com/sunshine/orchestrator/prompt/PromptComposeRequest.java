package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.context.AssembledContext;

import java.util.List;

/**
 * PromptComposer 输入 — 6 层叠加 + 场景覆盖层的上下文载体。
 * personalRules：用户个人规则（soul），非空时作为独立注入层（Gateway 链路在 base-system 之后，ReAct 链路在 mode-overlay 之后）。
 * kind：会话类型（"chat" / "task"），用于注入场景覆盖层 scene-overlay.{kind}。
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
        String workspaceCheckout) {

    public PromptComposeRequest {
        injectedUserContexts = injectedUserContexts != null ? List.copyOf(injectedUserContexts) : List.of();
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
        return new PromptComposeRequest(
                PromptMode.REACT, context, userMessage, null, skillId, null, injectedUserContexts, null,
                reactRestart, null, personalRules, kind, workspaceCheckout);
    }

    /** Planner-Executor — ReAct + {@code harnessPromptId}（mechanism overlay，仅 kind=planner） */
    public static PromptComposeRequest forPlannerHarness(
            AssembledContext context, String userMessage, List<String> injectedUserContexts,
            boolean reactRestart, String harnessPromptId, String kind, String workspaceCheckout) {
        return new PromptComposeRequest(
                PromptMode.REACT, context, userMessage, null, null, null, injectedUserContexts, null,
                reactRestart, harnessPromptId, null, kind, workspaceCheckout);
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
                personalRules, kind, null);
    }

}
