package com.sunshine.orchestrator.context.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.GcResultView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L1SnapshotView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L2StateView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L2UpdateRequest;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L3StatusView;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.ReingestResultView;
import com.sunshine.orchestrator.context.job.ContextMaintenanceService;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContextAdminService {

    private static final Set<String> ALLOWED_STATUS = Set.of("active", "superseded", "void");

    private final UserContextStateRepository l2Repository;
    private final ConversationContextL1Repository l1Repository;
    private final ConversationContextL1Store l1Store;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final L3IngestService l3IngestService;
    private final ContextMaintenanceService maintenanceService;
    private final ContextProperties contextProperties;

    public List<L2StateView> listL2(String userId, String tenantId) {
        requireText(userId, "userId");
        String tid = normalizeTenant(tenantId);
        return l2Repository.findByUserIdAndTenantIdOrderByUpdatedAtDesc(userId, tid).stream()
                .map(ContextAdminService::toL2View)
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
        ConversationContextL1Entity entity = l1Repository.findById(convId)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        Map<String, String> mid = l1Store.parseMidAnswers(entity);
        List<String> folded = new ArrayList<>(l1Store.parseFarFoldedMsgIds(entity));
        return new L1SnapshotView(
                entity.getConvId(),
                entity.getUserId(),
                entity.getTenantId(),
                mid,
                l1Store.farSummaryOf(entity),
                folded,
                entity.getNearN(),
                entity.getMidN(),
                entity.getUpdatedAt());
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
                "Milvus vector count unavailable from orchestrator; use reingest/GC for ops",
                l1Count,
                l3.getTopK(),
                l3.getMinScore());
    }

    public GcResultView runGc() {
        maintenanceService.runOnce();
        return new GcResultView(true, "maintenance runOnce completed");
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
        return new ReingestResultView(convId, count, "reingest submitted");
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
                e.getUpdatedAt());
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
