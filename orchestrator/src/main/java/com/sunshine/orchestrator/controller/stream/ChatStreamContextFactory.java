package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.memory.MemoryComposer;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.model.ChatMessage;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanParser;
import com.sunshine.orchestrator.routing.ExecutionPreference;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 新消息 / 续跑前的会话落库与 Memory 组装 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamContextFactory {

    private final ConversationService conversationService;
    private final DesensitizeClient desensitizeClient;
    private final SkillBindingParser skillBindingParser;
    private final MemoryComposer memoryComposer;
    private final ExecutionPlanStore executionPlanStore;
    private final ExecutionPlanParser executionPlanParser;

    @Autowired(required = false)
    private GenerationRegistry registry;

    @Value("${agent.history.max-messages:20}")
    private int maxHistoryMessages;

    public ChatStreamContext prepareNewMessage(ChatMessage msg, String userId, String tenantId) {
        ChatConversationEntity conv = resolveConversation(msg.getConversationId(), userId, tenantId);
        // 先加载历史再落库本轮 user/assistant，避免 history + userContent 重复注入 LLM
        List<ChatTurn> loadedHistory = conversationService.loadHistory(conv.getId(), maxHistoryMessages).stream()
                .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .collect(Collectors.toList());

        ExecutionPreference preference = ExecutionPreference.from(msg.getExecutionPreference());
        String userContent = desensitizeClient.scrub(msg.getContent());
        conversationService.appendMessage(conv.getId(), "user",
                userContent, MessageStatus.COMPLETED, preference.wireValue());
        ChatMessageEntity assistant = conversationService.appendMessage(
                conv.getId(), "assistant", "", MessageStatus.STREAMING);
        conv = conversationService.autoTitleIfDefault(conv.getId(), userId, tenantId, userContent);
        conversationService.updateExecutionPreference(
                conv.getId(), userId, tenantId, preference.wireValue());
        String executionQuery = userContent;
        if (preference.isForced() && !preference.allowsSkillBinding()) {
            executionQuery = skillBindingParser.stripAtMention(userContent);
        } else if (StringUtils.hasText(msg.getSkillId())) {
            executionQuery = skillBindingParser.stripSkillMentions(userContent);
        }
        MemoryContext memory = memoryComposer.compose(new MemoryComposer.ComposeRequest(
                userId, tenantId, conv.getId(), loadedHistory, executionQuery));
        if (!loadedHistory.isEmpty() && !memory.hasAnyLayer()) {
            log.debug("[Orchestrator] 记忆块为空 loaded={} user={}",
                    loadedHistory.size(),
                    executionQuery.length() > 40 ? executionQuery.substring(0, 40) + "..." : executionQuery);
        }

        return new ChatStreamContext(
                conv.getId(),
                assistant.getId(),
                conv.getTitle(),
                executionQuery,
                memory,
                "",
                "",
                null,
                null,
                true,
                userId,
                tenantId,
                preference,
                msg.getWorkflowId(),
                msg.getSkillId(),
                false);
    }

    public ChatResumePreparation buildResumePreparation(ChatMessage msg, String userId, String tenantId) {
        ChatMessageEntity assistant = conversationService.getMessageOwned(
                msg.getResumeMessageId(), userId, tenantId);
        if (registry != null && MessageStatus.STREAMING.equals(assistant.getStatus())
                && registry.findByMessageId(assistant.getId()).isEmpty()) {
            conversationService.forceInterruptedIfStreaming(assistant.getId());
            assistant = conversationService.getMessageOwned(msg.getResumeMessageId(), userId, tenantId);
        }
        final String assistantId = assistant.getId();
        conversationService.validateResumeAllowed(assistant, userId, tenantId);
        List<ProcessingStep> existingSteps = ProcessingStepMerger.fromJson(assistant.getSteps());
        boolean planWorkflowResume = executionPlanStore.findResumableForMessage(assistant.getId()).isPresent();
        ExecutionPlan storedPlan = executionPlanParser.parseStoredIntent(
                assistant.getIntent() != null ? assistant.getIntent() : "");
        boolean reactRestartResume = !planWorkflowResume && storedPlan.mode() == ExecutionMode.REACT;

        String resumeContent;
        String resumeReasoning;
        String stepsJson;
        String contentBlocksJson = null;
        if (reactRestartResume) {
            resumeContent = "";
            resumeReasoning = "";
            stepsJson = ProcessingStepMerger.toJson(ProcessingStepMerger.retainIntentStepsOnly(existingSteps));
            contentBlocksJson = "[]";
        } else if (planWorkflowResume) {
            resumeContent = "";
            resumeReasoning = "";
            stepsJson = assistant.getSteps();
        } else {
            resumeContent = assistant.getContent() != null ? assistant.getContent() : "";
            resumeReasoning = assistant.getReasoning() != null ? assistant.getReasoning() : "";
            stepsJson = assistant.getSteps();
        }

        List<ChatMessageEntity> historyEntities = conversationService.loadHistoryForResume(
                assistant.getConversationId(), assistant);
        String userContent = historyEntities.stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(ChatMessageEntity::getContent)
                .orElse("");

        List<ChatTurn> history = historyEntities.stream()
                .filter(m -> !m.getId().equals(assistantId))
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!history.isEmpty()
                && "user".equals(history.get(history.size() - 1).role())
                && history.get(history.size() - 1).content().equals(userContent)) {
            history.remove(history.size() - 1);
        }

        MemoryContext memory = memoryComposer.compose(new MemoryComposer.ComposeRequest(
                userId, tenantId, assistant.getConversationId(), history, userContent));
        // ReAct 续跑重规划：loadHistoryForResume 已在当前 assistant 前截断，并去掉同轮 user（作 query）；
        // STM 仅含更早已完成轮次，不含本轮 tool/正文执行史；Agent 侧靠新 ReActAgent + stream epoch 隔离。

        return new ChatResumePreparation(
                assistant.getId(),
                assistant.getConversationId(),
                userContent,
                memory,
                resumeContent,
                resumeReasoning,
                assistant.getIntent(),
                stepsJson,
                contentBlocksJson,
                reactRestartResume,
                userId,
                tenantId);
    }

    private ChatConversationEntity resolveConversation(String conversationId, String userId, String tenantId) {
        if (!StringUtils.hasText(conversationId)) {
            return conversationService.create(userId, tenantId);
        }
        return conversationService.getOwned(conversationId, userId, tenantId);
    }
}
