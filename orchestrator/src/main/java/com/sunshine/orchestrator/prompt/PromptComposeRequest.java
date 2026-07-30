package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.context.AssembledContext;

import java.util.List;

/**
 * PromptComposer 输入 — 6 层叠加的上下文载体。
 * personalRules：用户个人规则（soul），非空时作为独立注入层（Gateway 链路在 base-system 之后，ReAct 链路在 mode-overlay 之后）。
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
        String reactPromptId,
        String personalRules) {

    public PromptComposeRequest {
        injectedUserContexts = injectedUserContexts != null ? List.copyOf(injectedUserContexts) : List.of();
    }

    /** 直连 Gateway / DIRECT */
    public static PromptComposeRequest forDirect(AssembledContext context, String userMessage) {
        return forDirect(context, userMessage, null);
    }

    public static PromptComposeRequest forDirect(AssembledContext context, String userMessage, String personalRules) {
        return new PromptComposeRequest(
                PromptMode.DIRECT, context, userMessage, null, null, null, List.of(), null, false, null, personalRules);
    }

    /** 直连 Gateway / DIRECT 续写 */
    public static PromptComposeRequest forDirectContinue(
            AssembledContext context, String userMessage, String partialAssistant) {
        return forDirectContinue(context, userMessage, partialAssistant, null);
    }

    public static PromptComposeRequest forDirectContinue(
            AssembledContext context, String userMessage, String partialAssistant, String personalRules) {
        return new PromptComposeRequest(
                PromptMode.DIRECT, context, userMessage, null, null, null, List.of(), partialAssistant, false, null,
                personalRules);
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
            List<String> injectedUserContexts, boolean reactRestart, String reactPromptId) {
        return forReact(context, userMessage, skillId, injectedUserContexts, reactRestart, reactPromptId, null);
    }

    public static PromptComposeRequest forReact(
            AssembledContext context, String userMessage, String skillId,
            List<String> injectedUserContexts, boolean reactRestart, String reactPromptId, String personalRules) {
        return new PromptComposeRequest(
                PromptMode.REACT, context, userMessage, null, skillId, null, injectedUserContexts, null,
                reactRestart, reactPromptId, personalRules);
    }

    /** workflow llm 节点 — nodePrompt 为 TemplateResolver 渲染后的第 6 层 */
    public static PromptComposeRequest forWorkflowLlm(
            String workflowId, AssembledContext context, String userMessage, String nodePrompt) {
        return forWorkflowLlm(workflowId, context, userMessage, nodePrompt, null);
    }

    public static PromptComposeRequest forWorkflowLlm(
            String workflowId, AssembledContext context, String userMessage, String nodePrompt, String personalRules) {
        return new PromptComposeRequest(
                PromptMode.WORKFLOW, context, userMessage, workflowId, null, nodePrompt, List.of(), null, false, null,
                personalRules);
    }

    }

            String personalRules) {
        return new PromptComposeRequest(
                List.of(), null, false, null, personalRules);
    }
}
