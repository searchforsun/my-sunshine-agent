package com.sunshine.orchestrator.context.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 L1 审计决策写回 mid_answers / far_summary。 */
@Component
@RequiredArgsConstructor
public class L1AuditApplier {

    private static final ObjectMapper OM = new ObjectMapper();

    private final ConversationContextL1Repository l1Repository;
    private final ConversationContextL1Store l1Store;

    int apply(
            List<ConversationContextL1Entity> rows,
            ContextAuditDecisions.L1AuditDecision decision,
            Instant now) {
        if (decision == null || rows == null || rows.isEmpty()) {
            return 0;
        }
        Map<String, ConversationContextL1Entity> byConv = new HashMap<>();
        for (ConversationContextL1Entity row : rows) {
            if (row != null && StringUtils.hasText(row.getConvId())) {
                byConv.put(row.getConvId(), row);
            }
        }
        int patched = 0;
        Set<String> touched = new HashSet<>();
        for (Map.Entry<String, List<String>> e : decision.removeMidKeys().entrySet()) {
            ConversationContextL1Entity row = byConv.get(e.getKey());
            if (row == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            Map<String, String> mid = new LinkedHashMap<>(l1Store.parseMidAnswers(row));
            boolean changed = false;
            for (String msgId : e.getValue()) {
                if (StringUtils.hasText(msgId) && mid.containsKey(msgId)) {
                    mid.remove(msgId);
                    changed = true;
                }
            }
            if (changed) {
                row.setMidAnswers(writeMidJson(mid));
                touched.add(row.getConvId());
            }
        }
        for (Map.Entry<String, String> e : decision.farSummaryByConv().entrySet()) {
            ConversationContextL1Entity row = byConv.get(e.getKey());
            if (row == null || e.getValue() == null) {
                continue;
            }
            String next = e.getValue();
            String prev = row.getFarSummary() != null ? row.getFarSummary() : "";
            if (!prev.equals(next)) {
                row.setFarSummary(next);
                touched.add(row.getConvId());
            }
        }
        for (String convId : touched) {
            ConversationContextL1Entity row = byConv.get(convId);
            if (row == null) {
                continue;
            }
            row.setUpdatedAt(now);
            l1Repository.save(row);
            patched++;
        }
        return patched;
    }

    private static String writeMidJson(Map<String, String> mid) {
        try {
            return OM.writeValueAsString(new LinkedHashMap<>(mid != null ? mid : Map.of()));
        } catch (Exception e) {
            throw new IllegalStateException("mid_answers serialize failed", e);
        }
    }
}
