package com.sunshine.orchestrator.context.l3;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L3 按需召回：排除 L1 Near/Mid 已覆盖 msgId；时间衰减；渲染材料块（含 Far 回填）。
 * v28：chat 召回仅 semantic 摘要层（body 原文层已退役），杜绝正文零散 chunk 注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L3RecallService {

    public static final String MATERIAL_HEADER = "context.l3.material-header";

    private final ContextProperties contextProperties;
    private final HistoryRagClient historyRagClient;
    private final PromptCatalogHolder catalogHolder;

    /**
     * @param excludeMsgIds 本会话 Near/Mid 窗口已覆盖的 msgId（禁止重复注入）
     * @param farMsgIds     本会话 Far 带 msgId；far_summary 非空时命中则并入材料块（仍标可能过期）
     * @param farSummaryNonEmpty 当前会话 far_summary 是否非空
     */
    public String recall(
            String userId,
            String tenantId,
            String query,
            Set<String> excludeMsgIds,
            Set<String> farMsgIds,
            boolean farSummaryNonEmpty) {
        if (!contextProperties.isEnabled() || !StringUtils.hasText(userId) || !StringUtils.hasText(query)) {
            return "";
        }
        ContextProperties.L3 l3 = contextProperties.getL3();
        int topK = Math.max(1, l3.getTopK());
        // 多取一些，过滤 Near/Mid 后仍够 topK
        int fetchK = Math.min(50, Math.max(topK * 4, topK + (excludeMsgIds != null ? excludeMsgIds.size() : 0)));
        // v26 scene 隔离：chat 自动召回仅 scene=chat（task 消息经 scene 字段隔离，互不召回）
        // v28 layer 过滤：chat 仅 semantic 摘要层（body 原文层已退役）；process 层是 task 专属，chat 不召回
        List<HistoryRagClient.HistoryHit> raw;
        try {
            raw = historyRagClient.search(userId, tenantId, (String) null, "chat",
                            List.of("semantic"), query, fetchK)
                    .block();
        } catch (Exception e) {
            log.warn("[ContextL3] recall search 失败: {}", e.getMessage());
            return "";
        }
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Instant now = Instant.now();
        Set<String> exclude = excludeMsgIds != null ? excludeMsgIds : Set.of();
        Set<String> farIds = farMsgIds != null ? farMsgIds : Set.of();
        List<ScoredHit> kept = filterAndRank(raw, exclude, farIds, farSummaryNonEmpty, l3, now);
        if (kept.isEmpty()) {
            return "";
        }
        if (kept.size() > topK) {
            kept = kept.subList(0, topK);
        }
        String header = catalogHolder.requireText(MATERIAL_HEADER);
        if (!StringUtils.hasText(header)) {
            header = "[历史材料 · L3 · 可能过期]";
        }
        return renderBlock(header.strip(), kept);
    }

    static List<ScoredHit> filterAndRank(
            List<HistoryRagClient.HistoryHit> raw,
            Set<String> excludeMsgIds,
            Set<String> farMsgIds,
            boolean farSummaryNonEmpty,
            ContextProperties.L3 l3,
            Instant now) {
        double minScore = l3.getMinScore();
        boolean timeDecay = l3.isTimeDecay();
        double halfLife = l3.getDecayHalfLifeDays() > 0 ? l3.getDecayHalfLifeDays() : 90.0;
        // 同 msgId 只保留衰减后最高分 chunk
        Map<String, ScoredHit> bestByMsg = new LinkedHashMap<>();
        for (HistoryRagClient.HistoryHit hit : raw) {
            if (hit == null || !StringUtils.hasText(hit.content())) {
                continue;
            }
            String msgId = hit.msgId() != null ? hit.msgId() : "";
            if (StringUtils.hasText(msgId) && excludeMsgIds.contains(msgId)) {
                continue;
            }
            boolean inFar = StringUtils.hasText(msgId) && farMsgIds.contains(msgId);
            double score = hit.score();
            if (timeDecay) {
                score = applyTimeDecay(score, hit.createdAtMs(), now, halfLife);
            }
            // Far 已折叠 msgId 降权（非硬排除）：摘要可能丢细节，原文仍有补充价值
            if (inFar) {
                score *= 0.5;
            }
            if (score < minScore) {
                continue;
            }
            boolean farBackfill = inFar && farSummaryNonEmpty;
            ScoredHit scored = new ScoredHit(hit.convId(), msgId, hit.content(), score, farBackfill);
            ScoredHit prev = bestByMsg.get(msgId.isEmpty() ? hit.content() : msgId);
            if (prev == null || scored.score() > prev.score()) {
                bestByMsg.put(msgId.isEmpty() ? hit.content() : msgId, scored);
            }
        }
        List<ScoredHit> out = new ArrayList<>(bestByMsg.values());
        out.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        return out;
    }

    static double applyTimeDecay(double score, long createdAtMs, Instant now, double halfLifeDays) {
        if (createdAtMs <= 0 || now == null) {
            return score;
        }
        long ageMs = Math.max(0L, now.toEpochMilli() - createdAtMs);
        double ageDays = ageMs / 86_400_000.0;
        double factor = Math.pow(0.5, ageDays / halfLifeDays);
        return score * factor;
    }

    static String renderBlock(String header, List<ScoredHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append(header);
        Set<String> seen = new HashSet<>();
        for (ScoredHit h : hits) {
            String line = h.content().strip();
            if (!StringUtils.hasText(line) || !seen.add(line)) {
                continue;
            }
            sb.append('\n').append("- ").append(line);
        }
        return sb.length() > header.length() ? sb.toString() : "";
    }

    public record ScoredHit(String convId, String msgId, String content, double score, boolean farBackfill) {
    }
}
