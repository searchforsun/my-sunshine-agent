package com.sunshine.orchestrator.context.l1;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.context.ContextProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** L1 派生表读写：mid_answers JSON + far_summary + far_folded_msg_ids。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextL1Store {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final ConversationContextL1Repository repository;
    private final ContextProperties contextProperties;

    public Optional<ConversationContextL1Entity> find(String convId) {
        if (!StringUtils.hasText(convId)) {
            return Optional.empty();
        }
        return repository.findById(convId);
    }

    public Map<String, String> parseMidAnswers(ConversationContextL1Entity entity) {
        if (entity == null || !StringUtils.hasText(entity.getMidAnswers())) {
            return Map.of();
        }
        try {
            Map<String, String> map = OM.readValue(entity.getMidAnswers(), MAP_TYPE);
            return map != null ? Map.copyOf(map) : Map.of();
        } catch (Exception e) {
            log.warn("[ContextL1] mid_answers 解析失败 conv={}: {}", entity.getConvId(), e.getMessage());
            return Map.of();
        }
    }

    public String farSummaryOf(ConversationContextL1Entity entity) {
        if (entity == null || entity.getFarSummary() == null) {
            return "";
        }
        return entity.getFarSummary();
    }

    /** 已折叠进 far_summary 的消息 id（有序去重）。 */
    public Set<String> parseFarFoldedMsgIds(ConversationContextL1Entity entity) {
        if (entity == null || !StringUtils.hasText(entity.getFarFoldedMsgIds())) {
            return Set.of();
        }
        try {
            List<String> list = OM.readValue(entity.getFarFoldedMsgIds(), LIST_TYPE);
            if (list == null || list.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (String id : list) {
                if (StringUtils.hasText(id)) {
                    out.add(id);
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Exception e) {
            log.warn("[ContextL1] far_folded_msg_ids 解析失败 conv={}: {}", entity.getConvId(), e.getMessage());
            return Set.of();
        }
    }

    public void upsert(
            String userId,
            String tenantId,
            String convId,
            Map<String, String> midAnswers,
            String farSummary,
            Collection<String> farFoldedMsgIds,
            int nearN,
            int midN) {
        ConversationContextL1Entity entity = repository.findById(convId).orElseGet(() -> {
            ConversationContextL1Entity created = new ConversationContextL1Entity();
            created.setConvId(convId);
            return created;
        });
        entity.setUserId(userId);
        entity.setTenantId(tenantId != null ? tenantId : "default");
        entity.setMidAnswers(writeMidAnswers(midAnswers));
        entity.setFarSummary(farSummary != null ? farSummary : "");
        entity.setFarFoldedMsgIds(writeFarFoldedMsgIds(farFoldedMsgIds));
        entity.setNearN(nearN > 0 ? nearN : contextProperties.getL1().getNearTurns());
        entity.setMidN(midN > 0 ? midN : contextProperties.getL1().getMidTurns());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    private static String writeMidAnswers(Map<String, String> midAnswers) {
        Map<String, String> map = midAnswers != null ? midAnswers : Collections.emptyMap();
        try {
            return OM.writeValueAsString(new LinkedHashMap<>(map));
        } catch (Exception e) {
            throw new IllegalStateException("mid_answers serialize failed", e);
        }
    }

    private static String writeFarFoldedMsgIds(Collection<String> farFoldedMsgIds) {
        List<String> list = new ArrayList<>();
        if (farFoldedMsgIds != null) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String id : farFoldedMsgIds) {
                if (StringUtils.hasText(id) && seen.add(id)) {
                    list.add(id);
                }
            }
        }
        try {
            return OM.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("far_folded_msg_ids serialize failed", e);
        }
    }
}
