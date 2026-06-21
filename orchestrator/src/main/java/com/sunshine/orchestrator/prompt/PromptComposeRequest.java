package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.memory.MemoryContext;

import java.util.List;

/**
 * PromptComposer 输入 — 6 层叠加的上下文载体。
 */
public record PromptComposeRequest(
        PromptMode mode,
        MemoryContext memory,
        String userMessage,
        String workflowId,
        String skillId,
        String nodePrompt,
        List<String> injectedUserContexts,
        String partialAssistant) {

    public PromptComposeRequest {
        injectedUserContexts = injectedUserContexts != null ? List.copyOf(injectedUserContexts) : List.of();
    }

    public static PromptComposeRequest forSimpleLlm(MemoryContext memory, String userMessage) {
        return new PromptComposeRequest(
                PromptMode.SIMPLE_LLM, memory, userMessage, null, null, null, List.of(), null);
    }

    public static PromptComposeRequest forSimpleLlmContinue(
            MemoryContext memory, String userMessage, String partialAssistant) {
        return new PromptComposeRequest(
                PromptMode.SIMPLE_LLM, memory, userMessage, null, null, null, List.of(), partialAssistant);
    }

    public static PromptComposeRequest forReact(
            MemoryContext memory, String userMessage, List<String> injectedUserContexts) {
        return new PromptComposeRequest(
                PromptMode.REACT, memory, userMessage, null, null, null, injectedUserContexts, null);
    }

    /** workflow llm 节点 — nodePrompt 为 TemplateResolver 渲染后的第 6 层 */
    public static PromptComposeRequest forWorkflowLlm(
            String workflowId, MemoryContext memory, String userMessage, String nodePrompt) {
        return new PromptComposeRequest(
                PromptMode.WORKFLOW, memory, userMessage, workflowId, null, nodePrompt, List.of(), null);
    }
}
