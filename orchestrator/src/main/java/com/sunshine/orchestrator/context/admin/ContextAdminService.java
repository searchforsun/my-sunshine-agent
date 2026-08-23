package com.sunshine.orchestrator.context.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.ConversationSummaryView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.GcResultView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L1SnapshotView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L1WindowRowView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L2StateView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L2UpdateRequest;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L3EntryView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L3StatusView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.ReingestResultView;
import com.sunshine.orchestrator.context.job.ContextMaintenanceService;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import com.sunshine.orchestrator.context.l3.L3IngestService;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContextAdminService {

    private static final Set<String> ALLOWED_STATUS = Set.of("active", "superseded", "void", "conflict");

    private final UserContextStateRepository l2Repository;
    private final ConversationContextL1Repository l1Repository;
    private final ConversationContextL1Store l1Store;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final L3IngestService l3IngestService;
    private final HistoryRagClient historyRagClient;
    private final ContextMaintenanceService maintenanceService;
    private final ContextProperties contextProperties;

    public List<L2StateView> listL2(String userId, String tenantId) {
        requireText(userId, "userId");
        String tid = normalizeTenant(tenantId);
        return l2Repository.findByUserIdAndTenantIdOrderByUpdatedAtDesc(userId, tid).stream()
                .map(ContextAdminService::toL2View)
                .toList();
    }

    public List<ConversationSummaryView> listConversations(String userId, String tenantId) {
        requireText(userId, "userId");
        String tid = normalizeTenant(tenantId);
        return conversationRepository.findByUserIdAndTenantIdOrderByUpdatedAtDesc(userId, tid).stream()
                .map(c -> new ConversationSummaryView(
                        c.getId(),
                        StringUtils.hasText(c.getTitle()) ? c.getTitle() : "新对话",
                        StringUtils.hasText(c.getKind()) ? c.getKind() : "chat",
                        c.getWorkspaceId(),
                        c.getCheckoutPath(),
                        c.getCreatedAt(),
                        c.getUpdatedAt()))
                .toList();
    }

    public L2StateView updateL2(String id, L2UpdateRequest request) {
        requireText(id, "id");
        if (request == null) {
            throw new BizException(CommonErrorCode.BAD_REQUEST);
        }
        UserContextStateEntity entity = l2Repository.findById(id)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        if (request.stateValue() != null) {
            String value = request.stateValue().strip();
            if (!StringUtils.hasText(value)) {
                throw new BizException(CommonErrorCode.BAD_REQUEST);
            }
            entity.setStateValue(value);
        }
        if (request.confidence() != null) {
            double c = request.confidence();
            if (c < 0.0 || c > 1.0) {
                throw new BizException(CommonErrorCode.BAD_REQUEST);
            }
            entity.setConfidence(c);
        }
        if (request.status() != null) {
            String status = request.status().strip().toLowerCase(Locale.ROOT);
            if (!ALLOWED_STATUS.contains(status)) {
                throw new BizException(CommonErrorCode.BAD_REQUEST);
            }
            entity.setStatus(status);
        }
        entity.setUpdatedAt(Instant.now());
        return toL2View(l2Repository.save(entity));
    }

    public L2StateView voidL2(String id) {
        requireText(id, "id");
        UserContextStateEntity entity = l2Repository.findById(id)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        entity.setStatus("void");
        entity.setUpdatedAt(Instant.now());
        return toL2View(l2Repository.save(entity));
    }

    public L1SnapshotView getL1(String convId) {
        requireText(convId, "convId");
        ChatConversationEntity conv = conversationRepository.findById(convId)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        ConversationContextL1Entity entity = l1Repository.findById(convId).orElse(null);
        Map<String, String> mid = l1Store.parseMidAnswers(entity);
        List<String> folded = new ArrayList<>(l1Store.parseFarFoldedMsgIds(entity));
        String far = l1Store.farSummaryOf(entity);
        int nearN;
        int midN;
        Instant updatedAt;
        if (entity != null) {
            nearN = entity.getNearN() > 0
                    ? entity.getNearN()
                    : Math.max(1, contextProperties.getL1().getNearTurns());
            midN = entity.getMidN() >= 0
                    ? entity.getMidN()
                    : Math.max(0, contextProperties.getL1().getMidTurns());
            updatedAt = entity.getUpdatedAt();
        } else {
            nearN = Math.max(1, contextProperties.getL1().getNearTurns());
            midN = Math.max(0, contextProperties.getL1().getMidTurns());
            updatedAt = conv.getUpdatedAt() != null ? conv.getUpdatedAt() : Instant.now();
        }
        List<SessionTurn> history = new ArrayList<>();
        Map<String, Instant> times = new HashMap<>();
        for (ChatMessageEntity m : messageRepository.findByConversationIdOrderBySeqAsc(convId)) {
            if (m == null) {
                continue;
            }
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) {
                continue;
            }
            if (!MessageStatus.COMPLETED.equals(m.getStatus())) {
                continue;
            }
            String body = MessageBodyText.resolve(m);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            history.add(SessionTurn.of(m.getId(), m.getRole(), body));
            if (m.getCreatedAt() != null) {
                times.put(m.getId(), m.getCreatedAt());
            }
        }
        List<L1WindowRowView> rows = L1WindowRowBuilder.build(
                history, times, mid, far, updatedAt, nearN, midN);
        return new L1SnapshotView(
                conv.getId(),
                conv.getUserId(),
                conv.getTenantId(),
                mid,
                far != null ? far : "",
                folded,
                nearN,
                midN,
                updatedAt,
                rows);
    }

    public L3StatusView l3Status(String userId, String tenantId) {
        requireText(userId, "userId");
        String tid = normalizeTenant(tenantId);
        long l1Count = l1Repository.findByUserIdAndTenantId(userId, tid).size();
        ContextProperties.L3 l3 = contextProperties.getL3();
        return new L3StatusView(
                userId,
                tid,
                contextProperties.isEnabled(),
                l3.getCollection(),
                "L3 无硬过期；消息删除后由 GC 清理孤儿向量",
                l1Count,
                l3.getTopK(),
                l3.getMinScore());
    }

    public List<L3EntryView> listL3Entries(String convId) {
        requireText(convId, "convId");
        ChatConversationEntity conv = conversationRepository.findById(convId)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        Map<String, String> roleByMsg = new HashMap<>();
        for (ChatMessageEntity m : messageRepository.findByConversationIdOrderBySeqAsc(convId)) {
            if (m != null && StringUtils.hasText(m.getId()) && StringUtils.hasText(m.getRole())) {
                roleByMsg.put(m.getId(), m.getRole());
            }
        }
        List<HistoryRagClient.HistoryChunk> chunks = historyRagClient
                .listByConv(conv.getUserId(), conv.getTenantId(), convId, 200)
                .block();
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<L3EntryView> out = new ArrayList<>(chunks.size());
        for (HistoryRagClient.HistoryChunk c : chunks) {
            Instant created = c.createdAtMs() > 0 ? Instant.ofEpochMilli(c.createdAtMs()) : null;
            out.add(new L3EntryView(
                    c.msgId(),
                    roleByMsg.getOrDefault(c.msgId(), ""),
                    c.chunkIndex(),
                    c.content(),
                    created,
                    null));
        }
        return List.copyOf(out);
    }

    public GcResultView runGc() {
        maintenanceService.runOnce();
        return new GcResultView(true, "过期清理已完成");
    }

    public ReingestResultView reingest(String convId) {
        requireText(convId, "convId");
        ChatConversationEntity conv = conversationRepository.findById(convId)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        List<ChatMessageEntity> messages = messageRepository.findByConversationIdOrderBySeqAsc(convId);
        int count = 0;
        for (ChatMessageEntity m : messages) {
            if (m == null) {
                continue;
            }
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) {
                continue;
            }
            if (!MessageStatus.COMPLETED.equals(m.getStatus())) {
                continue;
            }
            String body = MessageBodyText.resolve(m);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            long createdAt = m.getCreatedAt() != null
                    ? m.getCreatedAt().toEpochMilli()
                    : System.currentTimeMillis();
            l3IngestService.ingest(
                    conv.getUserId(),
                    conv.getTenantId(),
                    convId,
                    m.getId(),
                    body,
                    createdAt);
            count++;
        }
        return new ReingestResultView(convId, count, "已提交重建，共 " + count + " 条");
    }

    private static L2StateView toL2View(UserContextStateEntity e) {
        return new L2StateView(
                e.getId(),
                e.getUserId(),
                e.getTenantId(),
                e.getKind(),
                e.getStateKey(),
                e.getStateValue(),
                e.getConfidence(),
                e.getStatus(),
                e.getExpiresAt(),
                e.getSourceMsgId(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getScope(),
                e.getWorkspaceId(),
                e.getBackground());
    }

    private static String normalizeTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
    }

    private static void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(CommonErrorCode.BAD_REQUEST);
        }
    }
}
