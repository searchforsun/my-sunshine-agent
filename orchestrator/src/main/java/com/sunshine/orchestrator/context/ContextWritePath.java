package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import com.sunshine.orchestrator.context.l2.L2ExtractService;
import com.sunshine.orchestrator.context.l3.L3IngestService;
import com.sunshine.orchestrator.biz.SceneWriteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * 对话完成后的上下文写路径：先 L2 抽取，再 L1 压缩（Far 可读本轮 L2），再 L3 ingest。
 * 独立 Bean + {@code @Async}，避免 Lifecycle 自调用导致异步失效。
 * <p>kind/scope/scene 路由决策在 {@link ContextWritePolicy}（写路由矩阵单点），
 * 本类负责编排执行顺序与账本过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextWritePath {

    private final ConversationService conversationService;
    private final L1Compressor l1Compressor;
    private final L2ExtractService l2ExtractService;
    private final L3IngestService l3IngestService;
    private final ContextWritePolicy writePolicy;
    private final SceneWriteResolver sceneWriteResolver;

    @Async
    public void runAsync(String messageId, String userId, String tenantId) {
        try {
            ChatMessageEntity assistant = conversationService.getMessageOwned(messageId, userId, tenantId);
            String convId = assistant.getConversationId();
            ChatConversationEntity conv = conversationService.getOwned(convId, userId, tenantId);
            List<ChatMessageEntity> messages = conversationService.getMessages(convId, userId, tenantId);
            // 写路径场景回退链（authority §5.5）：routing → embedding → auto-create；
            // 得到 scene 后作为 biz_scene_scope 注入偏好抽取
            String bizScene = sceneWriteResolver.resolve(userId, tenantId, convId, messages).orElse(null);
            List<SessionTurn> history = messages.stream()
                    .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                    .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                    .map(m -> SessionTurn.fromMessage(m.getId(), m.getRole(), MessageBodyText.resolve(m), m.getSteps(),
                            conv != null ? conv.getKind() : null))
                    .filter(t -> StringUtils.hasText(t.content()))
                    .toList();
            Instant msgAt = assistant.getCreatedAt() != null ? assistant.getCreatedAt() : Instant.now();
            ContextWritePolicy.WriteDecision decision = writePolicy.route(conv);
            log.info("[Context] writePath 路由决策 conv={} kind={} reason={} → l2={} l3={} scope={} bizScene={}",
                    convId, conv != null ? conv.getKind() : null, decision.reason(),
                    decision.writeL2() ? "写" : "不写", decision.writeL3() ? "写" : "不写",
                    decision.scope() != null ? decision.scope() : "-", bizScene != null ? bizScene : "-");
            if (decision.writeL2()) {
                if ("workspace".equals(decision.scope())) {
                    l2ExtractService.extractWorkspace(
                            decision.workspaceId(), tenantId, messageId, history, msgAt);
                } else {
                    l2ExtractService.extract(userId, tenantId, messageId, history, msgAt, bizScene);
                }
            }
            l1Compressor.compress(userId, tenantId, convId, history);
            if (decision.writeL3()) {
                ingestTurnPair(userId, tenantId, decision.scene(), conv, messages, assistant);
            }
        } catch (Exception e) {
            log.warn("[Context] writePath 失败 msg={}: {}", messageId, e.getMessage());
        }
    }

    private void ingestTurnPair(
            String userId,
            String tenantId,
            String scene,
            ChatConversationEntity conv,
            List<ChatMessageEntity> messages,
            ChatMessageEntity assistant) {
        ChatMessageEntity precedingUser = findPrecedingUser(messages, assistant);
        String userBody = precedingUser != null ? MessageBodyText.resolve(precedingUser) : "";
        String assistantBody = MessageBodyText.resolve(assistant);
        long pairAt = precedingUser != null && precedingUser.getCreatedAt() != null
                ? precedingUser.getCreatedAt().toEpochMilli()
                : System.currentTimeMillis();
        // v26.2 单入口：body 即时全量 / turn-pair 攒批 + 语义按轮门禁（abstain 轮不落库）由 L3IngestService 决策
        if (StringUtils.hasText(userBody) || StringUtils.hasText(assistantBody)) {
            l3IngestService.ingestTurnPair(
                    userId, tenantId, assistant.getConversationId(), scene,
                    precedingUser != null ? precedingUser.getId() : null, userBody,
                    assistant.getId(), assistantBody, pairAt);
        }
        // v26 process 层：仅 task 会话 assistant 消息的步骤摘要向量化（§7.4.4）
        if ("task".equals(scene) && StringUtils.hasText(assistant.getSteps())) {
            long createdAt = assistant.getCreatedAt() != null
                    ? assistant.getCreatedAt().toEpochMilli()
                    : System.currentTimeMillis();
            l3IngestService.ingestProcessAsync(
                    userId, tenantId, assistant.getConversationId(),
                    assistant.getId(), assistant.getSteps(), createdAt);
        }
    }

    private static ChatMessageEntity findPrecedingUser(
            List<ChatMessageEntity> messages, ChatMessageEntity assistant) {
        if (messages == null || assistant == null) {
            return null;
        }
        ChatMessageEntity prev = null;
        for (ChatMessageEntity m : messages) {
            if (m == null) {
                continue;
            }
            if (assistant.getId() != null && assistant.getId().equals(m.getId())) {
                return prev != null && "user".equals(prev.getRole()) ? prev : null;
            }
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                prev = m;
            }
        }
        return null;
    }
}
