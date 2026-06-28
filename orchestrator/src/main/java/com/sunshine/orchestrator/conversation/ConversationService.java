package com.sunshine.orchestrator.conversation;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.audit.AuditService;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ChatConversationRepository conversationRepo;
    private final ChatMessageRepository messageRepo;
    private final AuditService auditService;
    private final MessagePersistenceReconciler messagePersistenceReconciler;

    @Value("${agent.generation.orphan-timeout-sec:60}")
    private int orphanTimeoutSec;

    @Value("${agent.resume.max-resume-attempts:3}")
    private int maxResumeAttempts;

    @Transactional
    public ChatConversationEntity create(String userId, String tenantId) {
        Instant now = Instant.now();
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(newId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId != null ? tenantId : "default");
        entity.setTitle("新对话");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return conversationRepo.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ChatConversationEntity> list(String userId, String tenantId) {
        return conversationRepo.findByUserIdAndTenantIdOrderByUpdatedAtDesc(
                userId, tenantId != null ? tenantId : "default");
    }

    @Transactional(readOnly = true)
    public ChatConversationEntity getOwned(String id, String userId, String tenantId) {
        ChatConversationEntity conv = conversationRepo.findById(id)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.CONVERSATION_NOT_FOUND));
        if (!belongsTo(conv, userId, tenantId)) {
            throw new BizException(OrchestratorErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conv;
    }

    @Transactional(readOnly = true)
    public ChatConversationEntity getOwnedWithMessages(String id, String userId, String tenantId) {
        return getOwned(id, userId, tenantId);
    }

    @Transactional
    public List<ChatMessageEntity> getMessages(String conversationId, String userId, String tenantId) {
        getOwned(conversationId, userId, tenantId);
        List<ChatMessageEntity> messages = messageRepo.findByConversationIdOrderBySeqAsc(conversationId);
        for (ChatMessageEntity msg : messages) {
            messagePersistenceReconciler.reconcileStreamingAssistant(msg);
        }
        return messageRepo.findByConversationIdOrderBySeqAsc(conversationId);
    }

    @Transactional
    public ChatConversationEntity updateTitle(String id, String userId, String tenantId, String title) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setTitle(title);
        conv.setUpdatedAt(Instant.now());
        return conversationRepo.save(conv);
    }

    /** 记录本会话最近一次用户指定的 executionPreference（auto 也落库便于恢复） */
    @Transactional
    public void updateExecutionPreference(String id, String userId, String tenantId, String preference) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setExecutionPreference(preference);
        conv.setUpdatedAt(Instant.now());
        conversationRepo.save(conv);
    }

    @Transactional
    public void delete(String id, String userId, String tenantId) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        messageRepo.deleteByConversationId(id);
        conversationRepo.delete(conv);
    }

    @Transactional
    public ChatMessageEntity appendMessage(String convId, String role, String content) {
        return appendMessage(convId, role, content, MessageStatus.COMPLETED);
    }

    @Transactional
    public ChatMessageEntity appendMessage(String convId, String role, String content, String status) {
        return appendMessage(convId, role, content, status, null);
    }

    @Transactional
    public ChatMessageEntity appendMessage(
            String convId, String role, String content, String status, String executionPreference) {
        getOwnedInternal(convId);
        Instant now = Instant.now();
        int seq = messageRepo.findMaxSeq(convId) + 1;

        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(newId());
        msg.setConversationId(convId);
        msg.setSeq(seq);
        msg.setRole(role);
        msg.setContent(content != null ? content : "");
        msg.setStatus(status);
        if ("user".equals(role) && executionPreference != null && !executionPreference.isBlank()) {
            msg.setExecutionPreference(executionPreference.strip());
        }
        msg.setCreatedAt(now);
        msg.setUpdatedAt(now);

        ChatMessageEntity saved = messageRepo.save(msg);
        touchConversation(convId);
        return saved;
    }

    @Transactional
    public ChatMessageEntity updateMessageContent(String messageId, String content, String status) {
        return updateMessage(messageId, content, null, status);
    }

    /**
     * 流式 partial 落库 — 若消息已终态则跳过，避免异步 flush 覆盖 completed 状态。
     */
    @Transactional
    public ChatMessageEntity updateMessageContentIfStreaming(String messageId, String content) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        if (!MessageStatus.STREAMING.equals(msg.getStatus())) {
            return msg;
        }
        return updateMessageContent(messageId, content, MessageStatus.STREAMING);
    }

    @Transactional
    public ChatMessageEntity updateMessage(String messageId, String content, String reasoning, String status) {
        return updateMessage(messageId, content, reasoning, status, null);
    }

    @Transactional
    public ChatMessageEntity updateMessage(
            String messageId, String content, String reasoning, String status, String stepsJson) {
        return updateMessage(messageId, content, reasoning, status, stepsJson, null);
    }

    @Transactional
    public ChatMessageEntity updateMessage(
            String messageId,
            String content,
            String reasoning,
            String status,
            String stepsJson,
            String contentBlocksJson) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        msg.setContent(content != null ? content : "");
        if (reasoning != null) {
            msg.setReasoning(reasoning);
        }
        if (stepsJson != null) {
            msg.setSteps(stepsJson);
        }
        if (contentBlocksJson != null) {
            msg.setContentBlocks(contentBlocksJson);
        }
        msg.setStatus(status);
        msg.setUpdatedAt(Instant.now());
        ChatMessageEntity saved = messageRepo.save(msg);
        touchConversation(msg.getConversationId());
        auditService.auditAssistantMessage(saved);
        return saved;
    }

    @Transactional
    public ChatMessageEntity updateMessageIntent(String messageId, String intent) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        msg.setIntent(intent);
        msg.setUpdatedAt(Instant.now());
        return messageRepo.save(msg);
    }

    /** 保存结构化执行计划（intent 列写 intentLabel 兼容审计） */
    @Transactional
    public ChatMessageEntity updateMessageExecutionPlan(String messageId,
            com.sunshine.orchestrator.routing.ExecutionPlan plan) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        msg.setIntent(plan.intentLabel());
        msg.setExecutionMode(plan.mode().name().toLowerCase().replace('_', '-'));
        msg.setWorkflowId(plan.workflowId());
        msg.setUpdatedAt(Instant.now());
        return messageRepo.save(msg);
    }

    /** 关联动态 Plan 记录 */
    @Transactional
    public ChatMessageEntity linkMessageExecutionPlan(String messageId, String executionPlanId) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        msg.setExecutionPlanId(executionPlanId);
        msg.setUpdatedAt(Instant.now());
        return messageRepo.save(msg);
    }

    @Transactional
    public ChatMessageEntity incrementResumeCount(String messageId) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        msg.setResumeCount(msg.getResumeCount() + 1);
        msg.setUpdatedAt(Instant.now());
        return messageRepo.save(msg);
    }

    /** 续跑：在 generation 锁获取成功后再置 streaming，避免锁冲突后消息卡在 streaming */
    @Transactional
    public void commitResumeStart(String messageId, String resumeContent) {
        incrementResumeCount(messageId);
        updateMessageContent(messageId, resumeContent != null ? resumeContent : "", MessageStatus.STREAMING);
    }

    /** cancel 时 job 已不在内存：强制将 streaming 消息标为 interrupted */
    @Transactional
    public void forceInterruptedIfStreaming(String messageId) {
        ChatMessageEntity msg = messageRepo.findById(messageId).orElse(null);
        if (msg == null || !MessageStatus.STREAMING.equals(msg.getStatus())) {
            return;
        }
        msg.setStatus(MessageStatus.INTERRUPTED);
        msg.setUpdatedAt(Instant.now());
        messageRepo.save(msg);
    }

    @Transactional(readOnly = true)
    public ChatMessageEntity getMessageOwned(String messageId, String userId, String tenantId) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        getOwned(msg.getConversationId(), userId, tenantId);
        return msg;
    }

    @Transactional(readOnly = true)
    public ChatMessageEntity findLastAssistantMessage(String convId) {
        return messageRepo.findTopByConversationIdOrderBySeqDesc(convId)
                .filter(m -> "assistant".equals(m.getRole()))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageEntity> loadHistory(String convId, int maxMessages) {
        List<ChatMessageEntity> recent = messageRepo.findRecentByConversationIdDesc(convId, maxMessages);
        List<ChatMessageEntity> history = new ArrayList<>(recent);
        Collections.reverse(history);
        return history;
    }

    /**
     * 加载续传上下文：截止到指定 assistant 消息对应的 user 消息（含 user，不含 assistant partial）
     */
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> loadHistoryForResume(String convId, ChatMessageEntity assistantMsg) {
        List<ChatMessageEntity> all = messageRepo.findByConversationIdOrderBySeqAsc(convId);
        List<ChatMessageEntity> history = new ArrayList<>();
        for (ChatMessageEntity m : all) {
            if (m.getId().equals(assistantMsg.getId())) {
                break;
            }
            if (MessageStatus.STREAMING.equals(m.getStatus())) {
                continue;
            }
            history.add(m);
        }
        return history;
    }

    @Transactional
    public ChatMessageEntity markOrphanStreamingAsInterrupted(String messageId) {
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        if (!MessageStatus.STREAMING.equals(msg.getStatus())) {
            return msg;
        }
        Instant threshold = Instant.now().minusSeconds(orphanTimeoutSec);
        if (msg.getUpdatedAt().isAfter(threshold)) {
            return msg;
        }
        msg.setStatus(MessageStatus.INTERRUPTED);
        msg.setUpdatedAt(Instant.now());
        return messageRepo.save(msg);
    }

    @Transactional
    public void validateResumeAllowed(ChatMessageEntity msg, String userId, String tenantId) {
        getOwned(msg.getConversationId(), userId, tenantId);

        if (!"assistant".equals(msg.getRole())) {
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }

        markOrphanStreamingAsInterrupted(msg.getId());
        final ChatMessageEntity assistant = messageRepo.findById(msg.getId()).orElseThrow();

        String status = assistant.getStatus();
        if (MessageStatus.STREAMING.equals(status)) {
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }
        if (!MessageStatus.isResumable(status)) {
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }

        ChatMessageEntity lastAssistant = findLastAssistantMessage(assistant.getConversationId());
        if (lastAssistant == null || !lastAssistant.getId().equals(assistant.getId())) {
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }

        if (messageRepo.countByConversationIdAndSeqGreaterThan(
                assistant.getConversationId(), assistant.getSeq()) > 0) {
            List<ChatMessageEntity> after = messageRepo.findByConversationIdOrderBySeqAsc(assistant.getConversationId())
                    .stream()
                    .filter(m -> m.getSeq() > assistant.getSeq())
                    .toList();
            boolean hasNewUser = after.stream().anyMatch(m -> "user".equals(m.getRole()));
            if (hasNewUser) {
                throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
            }
        }

        if (assistant.getResumeCount() >= maxResumeAttempts) {
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }
    }

    public int getMaxResumeAttempts() {
        return maxResumeAttempts;
    }

    private void getOwnedInternal(String convId) {
        conversationRepo.findById(convId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.CONVERSATION_NOT_FOUND));
    }

    private void touchConversation(String convId) {
        conversationRepo.findById(convId).ifPresent(conv -> {
            conv.setUpdatedAt(Instant.now());
            conversationRepo.save(conv);
        });
    }

    private static boolean belongsTo(ChatConversationEntity conv, String userId, String tenantId) {
        String tid = tenantId != null ? tenantId : "default";
        return conv.getUserId().equals(userId) && conv.getTenantId().equals(tid);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
