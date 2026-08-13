package com.sunshine.orchestrator.controller.stream;

import com.sunshine.orchestrator.agent.ReactCheckpointService;
import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.model.ChatMessage;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.rag.DefaultKbResolver;
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

/** 新消息 / 续跑前的会话落库与 Context 组装 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamContextFactory {

    private final ConversationService conversationService;
    private final DesensitizeClient desensitizeClient;
    private final SkillBindingParser skillBindingParser;
    private final ContextAssembler contextAssembler;
    private final ExecutionPlanStore executionPlanStore;
    private final ExecutionPlanParser executionPlanParser;
    private final DefaultKbResolver defaultKbResolver;
    private final ReactCheckpointService checkpointService;
    private final PromptCatalogHolder catalogHolder;

    /** INTERRUPTED assistant 折叠注记的 Catalog id（方案 A · 中断感知，见五层 spec §5.5.7 v16） */
    private static final String INTERRUPTED_MARKER = "context.l1.interrupted-marker";

    @Autowired(required = false)
    private GenerationRegistry registry;

    @Value("${agent.history.max-messages:20}")
    private int maxHistoryMessages;

    public ChatStreamContext prepareNewMessage(ChatMessage msg, String userId, String tenantId) {
        ChatConversationEntity conv = resolveConversation(msg.getConversationId(), userId, tenantId);
        // 先加载历史再落库本轮 user/assistant，避免 history + userContent 重复注入 LLM
        List<SessionTurn> loadedHistory = conversationService.loadHistory(conv.getId(), maxHistoryMessages).stream()
                .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                .map(m -> SessionTurn.of(m.getId(), m.getRole(), resolveBodyWithInterruptMark(m)))
                .filter(t -> StringUtils.hasText(t.content()))
                .collect(Collectors.toList());

        ExecutionPreference preference = ExecutionPreference.from(msg.resolveExecutionModeWire());
        String userContent = desensitizeClient.scrub(msg.getContent());
        boolean firstMessage = ConversationService.DEFAULT_TITLE.equals(conv.getTitle());
        conversationService.appendMessage(conv.getId(), "user",
                userContent, MessageStatus.COMPLETED, preference.wireValue());
        ChatMessageEntity assistant = conversationService.appendMessage(
                conv.getId(), "assistant", "", MessageStatus.STREAMING);
        conv = conversationService.autoTitleIfDefault(conv.getId(), userId, tenantId, userContent);
        conversationService.updateExecutionPreference(
                conv.getId(), userId, tenantId, preference.wireValue());
        String kbId = resolveSessionKbId(msg.getKbId(), conv.getKbId(), tenantId);
        if (StringUtils.hasText(msg.getKbId()) || !StringUtils.hasText(conv.getKbId())) {
            conversationService.updateKbId(conv.getId(), userId, tenantId, kbId);
        }
        String modelOverride = resolveSessionModelName(msg.getModelName(), conv.getModelName());
        if (msg.getModelName() != null) {
            conversationService.updateModelName(conv.getId(), userId, tenantId, modelOverride);
        }
        String executionQuery = userContent;
        if (preference.isForced() && !preference.allowsSkillBinding()) {
            executionQuery = skillBindingParser.stripAtMention(userContent);
        } else if (StringUtils.hasText(msg.getSkillId())) {
            executionQuery = skillBindingParser.stripSkillMentions(userContent);
        }
        AssembledContext memory = contextAssembler.assemble(new ContextAssembler.AssembleRequest(
                userId, tenantId, conv.getId(), loadedHistory, executionQuery, modelOverride));
        if (!loadedHistory.isEmpty() && !memory.hasAnyLayer()) {
            log.debug("[Orchestrator] 上下文为空 loaded={} user={}",
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
                firstMessage,
                userId,
                tenantId,
                preference,
                msg.getWorkflowId(),
                msg.getSkillId(),
                kbId,
                false,
                msg.getPersonalRules(),
                conv.getKind(),
                modelOverride);
    }
    /**
     * 中断感知（方案 A · 五层 spec §5.5.7 v16）：INTERRUPTED 的 assistant 消息折叠为显式中断注记，
     * 使后续轮次从 Near 中感知「上一轮被中断」；正文非空时连同已生成部分一起注入（信息不丢），
     * 正文为空时仅注记（保证不再被 hasText 过滤掉）。COMPLETED/FAILED 与 user 消息保持原样。
     * Catalog 缺失或读取失败时降级保留原正文（行为等同现状）。
     */
    private String resolveBodyWithInterruptMark(ChatMessageEntity m) {
        String body = MessageBodyText.resolve(m);
        if (!"assistant".equals(m.getRole()) || !MessageStatus.INTERRUPTED.equals(m.getStatus())) {
            return body;
        }
        String marker;
        try {
            marker = catalogHolder.requireText(INTERRUPTED_MARKER);
        } catch (RuntimeException e) {
            log.warn("[ChatStreamContextFactory] interrupted-marker 读取失败，降级原文: {}", e.getMessage());
            return body;
        }
        if (!StringUtils.hasText(marker)) {
            return body;
        }
        if (!StringUtils.hasText(body)) {
            return marker;
        }
        return marker + "\n\n已生成部分：\n" + body;
    }

    private String resolveSessionKbId(String requestKbId, String storedKbId, String tenantId) {
        if (StringUtils.hasText(requestKbId)) {
            return requestKbId.strip();
        }
        if (StringUtils.hasText(storedKbId)) {
            return storedKbId.strip();
        }
        return defaultKbResolver.resolveBlocking(tenantId, null);
    }

    /** 请求显式传 modelName（含空串清绑定）优先；否则沿用会话已存值 */
    private static String resolveSessionModelName(String requestModelName, String storedModelName) {
        if (requestModelName != null) {
            return StringUtils.hasText(requestModelName) ? requestModelName.strip() : null;
        }
        return StringUtils.hasText(storedModelName) ? storedModelName.strip() : null;
    }

    public ChatResumePreparation buildResumePreparation(ChatMessage msg, String userId, String tenantId) {
        ChatMessageEntity assistant = conversationService.getMessageOwned(
                msg.getResumeMessageId(), userId, tenantId);
        // 消息仍在流式（DB STREAMING）时 resume：先取消该消息的活跃 generation（若有），
        // 再强制标中断后继续。resume 语义即「取消旧 run 重来」，与 startResumeWithRedis 的
        // findByMessageId.cancel 对齐；否则 validateResumeAllowed 会因 STREAMING 直接拒绝，
        // 导致刷新失联后用户点「重新生成」永远无法恢复（旧 job 的异步 persistFinal 又更新滞后）。
        if (registry != null && MessageStatus.STREAMING.equals(assistant.getStatus())) {
            registry.findByMessageId(assistant.getId())
                    .ifPresent(job -> registry.cancel(job.getGenerationId()));
            conversationService.forceInterruptedIfStreaming(assistant.getId());
            assistant = conversationService.getMessageOwned(msg.getResumeMessageId(), userId, tenantId);
        }
        final String assistantId = assistant.getId();
        conversationService.validateResumeAllowed(assistant, userId, tenantId);
        ChatConversationEntity conv = conversationService.getOwned(assistant.getConversationId(), userId, tenantId);
        String kbId = resolveSessionKbId(msg.getKbId(), conv.getKbId(), tenantId);
        boolean planWorkflowResume = executionPlanStore.findResumableForMessage(assistant.getId()).isPresent();
        ExecutionPlan storedPlan = executionPlanParser.parseStoredIntent(
                assistant.getIntent() != null ? assistant.getIntent() : "");
        boolean reactRestartResume = !planWorkflowResume
                && storedPlan.mode() == ExecutionMode.FAST;
        boolean hasNativeCheckpoint = reactRestartResume
                && checkpointService.hasCheckpoint(userId, assistantId);
        log.info("[ChatStreamContextFactory] resume userId={} msg={} reactRestart={} hasNativeCheckpoint={}",
                userId, assistantId, reactRestartResume, hasNativeCheckpoint);

        String resumeContent;
        String resumeReasoning;
        String stepsJson;
        String contentBlocksJson = null;
        if (reactRestartResume) {
            resumeContent = "";
            resumeReasoning = "";
            // 无论有无 native checkpoint，时间线都保留（截断在 ChatController）；
            // 无 checkpoint 时靠 injectedBlocks 续进度，勿只留 intent
            stepsJson = assistant.getSteps();
            contentBlocksJson = assistant.getContentBlocks();
        } else if (planWorkflowResume) {
            resumeContent = "";
            resumeReasoning = "";
            stepsJson = assistant.getSteps();
        } else {
            resumeContent = MessageBodyText.resolve(assistant);
            resumeReasoning = assistant.getReasoning() != null ? assistant.getReasoning() : "";
            stepsJson = assistant.getSteps();
        }

        List<ChatMessageEntity> historyEntities = conversationService.loadHistoryForResume(
                assistant.getConversationId(), assistant);
        String userContent = historyEntities.stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(MessageBodyText::resolve)
                .orElse("");

        List<SessionTurn> history = historyEntities.stream()
                .filter(m -> !m.getId().equals(assistantId))
                .map(m -> SessionTurn.of(m.getId(), m.getRole(), resolveBodyWithInterruptMark(m)))
                .filter(t -> StringUtils.hasText(t.content()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!history.isEmpty()
                && "user".equals(history.get(history.size() - 1).role())
                && history.get(history.size() - 1).content().equals(userContent)) {
            history.remove(history.size() - 1);
        }

        AssembledContext memory = contextAssembler.assemble(new ContextAssembler.AssembleRequest(
                userId, tenantId, assistant.getConversationId(), history, userContent, conv.getModelName()));
        // ReAct 继续生成：loadHistoryForResume 已在当前 assistant 前截断，并去掉同轮 user（作 query）；
        // Near 仅含更早已完成轮次；本轮进度靠 checkpoint（若有）+ injectedBlocks（skill/tasks/think/tool）。

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
                tenantId,
                kbId,
                conv.getKind(),
                conv.getModelName());
    }

    private ChatConversationEntity resolveConversation(String conversationId, String userId, String tenantId) {
        if (!StringUtils.hasText(conversationId)) {
            return conversationService.create(userId, tenantId);
        }
        return conversationService.getOwned(conversationId, userId, tenantId);
    }
}
