package com.sunshine.orchestrator.conversation;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.audit.AuditService;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.conversation.dto.ConversationPageDto;
import com.sunshine.orchestrator.conversation.dto.ConversationDetailDto;
import com.sunshine.orchestrator.conversation.dto.ConversationSearchDto;
import com.sunshine.orchestrator.conversation.dto.ConversationSummaryDto;
import com.sunshine.orchestrator.conversation.dto.MessagePageDto;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.routing.ExecutionPreference;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ChatConversationRepository conversationRepo;
    private final ChatMessageRepository messageRepo;
    private final AuditService auditService;
    private final MessagePersistenceReconciler messagePersistenceReconciler;
    private final SandboxSessionLifecycle sandboxSessionLifecycle;

    @Value("${agent.generation.orphan-timeout-sec:60}")
    private int orphanTimeoutSec;

    @Value("${agent.resume.max-resume-attempts:3}")
    private int maxResumeAttempts;

    @Transactional
    public ChatConversationEntity create(String userId, String tenantId) {
        return create(userId, tenantId, "chat", null, null);
    }

    public ChatConversationEntity create(String userId, String tenantId,
                                          String kind, String workspaceId, String checkoutPath) {
        Instant now = Instant.now();
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(newId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId != null ? tenantId : "default");
        entity.setTitle("新对话");
        entity.setKind(kind != null && !kind.isBlank() ? kind : "chat");
        entity.setWorkspaceId(workspaceId);
        entity.setCheckoutPath(checkoutPath);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return conversationRepo.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ChatConversationEntity> list(String userId, String tenantId) {
        return conversationRepo.findByUserIdAndTenantIdOrderByUpdatedAtDesc(
                userId, tenantId != null ? tenantId : "default");
    }

    /**
     * 侧栏分页：kind=chat 排除 task；kind=task 须带 workspaceId。
     * 多取 1 条判定 hasMore；offset 按 limit 对齐（page = offset/limit）。
     */
    @Transactional(readOnly = true)
    public ConversationPageDto listPage(
            String userId,
            String tenantId,
            String kind,
            String workspaceId,
            int offset,
            int limit) {
        String tid = tenantId != null ? tenantId : "default";
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        int off = Math.max(0, offset);
        int page = off / pageSize;
        var pageable = PageRequest.of(page, pageSize + 1);
        List<ChatConversationEntity> rows;
        String normalizedKind = kind == null ? "" : kind.strip().toLowerCase();
        if ("task".equals(normalizedKind)) {
            if (!StringUtils.hasText(workspaceId)) {
                return ConversationPageDto.builder().items(List.of()).hasMore(false).build();
            }
            rows = conversationRepo.findTaskPageByWorkspace(userId, tid, workspaceId.strip(), pageable);
        } else {
            // chat / 缺省：对话侧栏
            rows = conversationRepo.findChatPage(userId, tid, pageable);
        }
        boolean hasMore = rows.size() > pageSize;
        List<ConversationSummaryDto> items = (hasMore ? rows.subList(0, pageSize) : rows).stream()
                .map(ConversationSummaryDto::from)
                .toList();
        return ConversationPageDto.builder().items(items).hasMore(hasMore).build();
    }

    /**
     * 聚合搜索：按标题命中 + 消息正文命中取并集，按更新时间倒序返回。
     * 使用 LOCATE 做包含匹配，关键词按字面处理，天然规避 LIKE 通配符转义。
     */
    @Transactional(readOnly = true)
    public List<ConversationSearchDto> search(String userId, String tenantId, String keyword) {
        String kw = keyword == null ? "" : keyword.strip();
        if (kw.isEmpty()) {
            return List.of();
        }
        Map<String, ChatConversationEntity> byId = new LinkedHashMap<>();
        for (ChatConversationEntity conv : conversationRepo.searchByTitle(userId, tenantId, kw)) {
            byId.put(conv.getId(), conv);
        }
        List<ChatConversationEntity> contentMatches =
                conversationRepo.searchByMessageContent(userId, tenantId, kw);
        for (ChatConversationEntity conv : contentMatches) {
            byId.put(conv.getId(), conv);
        }

        List<ConversationSearchDto> results = byId.values().stream()
                .map(conv -> ConversationSearchDto.builder()
                        .id(conv.getId())
                        .title(conv.getTitle())
                        .kind(conv.getKind())
                        .workspaceId(conv.getWorkspaceId())
                        .createdAt(conv.getCreatedAt())
                        .updatedAt(conv.getUpdatedAt())
                        .build())
                .sorted(Comparator.comparing(ConversationSearchDto::getUpdatedAt).reversed())
                .toList();

        if (contentMatches.isEmpty()) {
            return results;
        }
        Map<String, String> latestHitByConv = latestMessageContentHits(
                contentMatches.stream().map(ChatConversationEntity::getId).toList(), kw);
        return results.stream()
                .map(dto -> {
                    String hit = latestHitByConv.get(dto.getId());
                    if (hit == null) {
                        return dto;
                    }
                    dto.setSnippet(buildSnippet(hit, kw));
                    return dto;
                })
                .toList();
    }

    /** 各内容命中会话取最新一条命中消息正文（msg seq 倒序，首个即最新） */
    private Map<String, String> latestMessageContentHits(List<String> convIds, String keyword) {
        Map<String, String> latest = new HashMap<>();
        for (Object[] row : messageRepo.findLatestMatchByConversationIds(convIds, keyword)) {
            String convId = String.valueOf(row[0]);
            if (!latest.containsKey(convId)) {
                latest.put(convId, row[1] == null ? "" : String.valueOf(row[1]));
            }
        }
        return latest;
    }

    /** 命中正文摘要：压缩空白后取关键词前后片段，控制在 120 字内便于列表展示 */
    static String buildSnippet(String content, String keyword) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String text = content.replaceAll("\\s+", " ").strip();
        int maxLen = 120;
        if (text.length() <= maxLen) {
            return text;
        }
        int idx = text.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) {
            return text.substring(0, maxLen) + "…";
        }
        int start = Math.max(0, idx - 40);
        int end = Math.min(text.length(), idx + keyword.length() + 60);
        String snippet = text.substring(start, end);
        if (start > 0) {
            snippet = "…" + snippet;
        }
        if (end < text.length()) {
            snippet += "…";
        }
        return snippet;
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

    /**
     * 会话消息游标分页：beforeSeq<=0 时取最近 limit 条（IM 首屏），否则取 seq<beforeSeq 的最近 limit 条。
     * 返回升序，hasMore 表示更早历史仍存在。
     */
    @Transactional
    public MessagePageDto getMessagesPage(String conversationId, String userId, String tenantId,
            int beforeSeq, int limit) {
        getOwned(conversationId, userId, tenantId);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        List<ChatMessageEntity> rows = beforeSeq > 0
                ? messageRepo.findPageBeforeSeqDesc(conversationId, beforeSeq, pageSize)
                : messageRepo.findRecentByConversationIdDesc(conversationId, pageSize);
        for (ChatMessageEntity msg : rows) {
            messagePersistenceReconciler.reconcileStreamingAssistant(msg);
        }
        rows.sort(Comparator.comparingInt(ChatMessageEntity::getSeq));
        int oldestSeq = rows.stream()
                .mapToInt(ChatMessageEntity::getSeq)
                .min().orElse(beforeSeq > 0 ? beforeSeq : 0);
        boolean hasMore = messageRepo.countByConversationIdAndSeqLessThan(conversationId, oldestSeq) > 0;
        return MessagePageDto.builder()
                .messages(rows.stream().map(ConversationDetailDto.MessageDto::from).toList())
                .hasMore(hasMore)
                .build();
    }

    public static final String DEFAULT_TITLE = "新对话";
    public static final int AUTO_TITLE_MAX_LEN = 15;

    @Transactional
    public ChatConversationEntity updateTitle(String id, String userId, String tenantId, String title) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setTitle(title);
        conv.setUpdatedAt(Instant.now());
        return conversationRepo.save(conv);
    }

    /** 切换分支后重绑定会话工作区 checkout 目录（task 会话 checkoutPath 随发送时的分支切换更新） */
    @Transactional
    public ChatConversationEntity updateCheckoutPath(String id, String userId, String tenantId, String checkoutPath) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setCheckoutPath(checkoutPath);
        conv.setUpdatedAt(Instant.now());
        return conversationRepo.save(conv);
    }

    /** 首条 user 消息后立即从正文推导标题（仍为默认「新对话」时），避免流式过程中 loadDetail 回退 */
    @Transactional
    public ChatConversationEntity autoTitleIfDefault(String id, String userId, String tenantId, String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return getOwned(id, userId, tenantId);
        }
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        if (!DEFAULT_TITLE.equals(conv.getTitle())) {
            return conv;
        }
        return updateTitle(id, userId, tenantId, deriveAutoTitle(userContent));
    }

    public static String deriveAutoTitle(String userContent) {
        String trimmed = userContent.strip();
        return trimmed.length() > AUTO_TITLE_MAX_LEN
                ? trimmed.substring(0, AUTO_TITLE_MAX_LEN)
                : trimmed;
    }

    /** 记录本会话最近一次用户指定的执行模式（列名 execution_preference；取值 fast|pro|workflow） */
    @Transactional
    public void updateExecutionPreference(String id, String userId, String tenantId, String preference) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setExecutionPreference(ExecutionPreference.toStoredWire(preference));
        conv.setUpdatedAt(Instant.now());
        conversationRepo.save(conv);
    }

    /** 记录本会话绑定的知识库 id */
    @Transactional
    public void updateKbId(String id, String userId, String tenantId, String kbId) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setKbId(kbId);
        conv.setUpdatedAt(Instant.now());
        conversationRepo.save(conv);
    }

    /** 记录本会话绑定的模型名（空则清除，走 chat scene） */
    @Transactional
    public void updateModelName(String id, String userId, String tenantId, String modelName) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        conv.setModelName(StringUtils.hasText(modelName) ? modelName.strip() : null);
        conv.setUpdatedAt(Instant.now());
        conversationRepo.save(conv);
    }

    @Transactional
    public void delete(String id, String userId, String tenantId) {
        ChatConversationEntity conv = getOwned(id, userId, tenantId);
        sandboxSessionLifecycle.destroyConversationSession(tenantId, id);
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
        if ("user".equals(role)) {
            String wire = ExecutionPreference.toStoredWire(executionPreference);
            if (wire != null) {
                msg.setExecutionPreference(wire);
            }
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

    /** HITL/Recovery 待确认：续跑阻塞期间增量落库 steps，避免刷新仍见暂停快照 */
    @Transactional
    public ChatMessageEntity updateMessageStepsIfStreaming(String messageId, String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return messageRepo.findById(messageId).orElse(null);
        }
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.MESSAGE_NOT_FOUND));
        if (!MessageStatus.STREAMING.equals(msg.getStatus())) {
            return msg;
        }
        msg.setSteps(stepsJson);
        msg.setUpdatedAt(Instant.now());
        ChatMessageEntity saved = messageRepo.save(msg);
        touchConversation(msg.getConversationId());
        return saved;
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

    /** ReAct/Plan 续跑重置：同步清空或覆盖 reasoning / steps / contentBlocks */
    @Transactional
    public void commitResumeStart(
            String messageId,
            String resumeContent,
            String resumeReasoning,
            String stepsJson,
            String contentBlocksJson) {
        incrementResumeCount(messageId);
        updateMessage(
                messageId,
                resumeContent != null ? resumeContent : "",
                resumeReasoning,
                MessageStatus.STREAMING,
                stepsJson,
                contentBlocksJson);
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
            log.warn("[Resume] 拒绝：非 assistant 消息 msg={} role={}", msg.getId(), msg.getRole());
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }

        markOrphanStreamingAsInterrupted(msg.getId());
        final ChatMessageEntity assistant = messageRepo.findById(msg.getId()).orElseThrow();

        String status = assistant.getStatus();
        if (MessageStatus.STREAMING.equals(status)) {
            log.warn("[Resume] 拒绝：消息仍为 STREAMING msg={} updatedAt={} orphanTimeoutSec={}",
                    assistant.getId(), assistant.getUpdatedAt(), orphanTimeoutSec);
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }
        if (!MessageStatus.isResumable(status)) {
            log.warn("[Resume] 拒绝：状态不可续传 msg={} status={}", assistant.getId(), status);
            throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
        }

        ChatMessageEntity lastAssistant = findLastAssistantMessage(assistant.getConversationId());
        if (lastAssistant == null || !lastAssistant.getId().equals(assistant.getId())) {
            log.warn("[Resume] 拒绝：非最后一条 assistant msg={} conv={} lastId={}",
                    assistant.getId(), assistant.getConversationId(),
                    lastAssistant == null ? null : lastAssistant.getId());
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
                log.warn("[Resume] 拒绝：assistant 之后有新 user 消息 msg={} conv={} afterCount={}",
                        assistant.getId(), assistant.getConversationId(), after.size());
                throw new BizException(OrchestratorErrorCode.RESUME_NOT_ALLOWED);
            }
        }

        if (assistant.getResumeCount() >= maxResumeAttempts) {
            log.warn("[Resume] 拒绝：超过最大续传次数 msg={} resumeCount={} max={}",
                    assistant.getId(), assistant.getResumeCount(), maxResumeAttempts);
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
