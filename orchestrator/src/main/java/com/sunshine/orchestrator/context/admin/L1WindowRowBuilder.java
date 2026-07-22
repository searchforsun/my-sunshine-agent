package com.sunshine.orchestrator.context.admin;

import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L1WindowRowView;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Admin L1 列表：近→中→远，区内新→旧；中窗 user 原文、assistant 用 mid_answers。
 */
final class L1WindowRowBuilder {

    private L1WindowRowBuilder() {
    }

    static List<L1WindowRowView> build(
            List<SessionTurn> history,
            Map<String, Instant> timesByMsgId,
            Map<String, String> midAnswers,
            String farSummary,
            Instant farAt,
            int nearN,
            int midN) {
        List<List<SessionTurn>> rounds = L1Compressor.groupRounds(history);
        int nearCap = Math.max(1, nearN);
        int midCap = Math.max(0, midN);
        int size = rounds.size();
        int nearStart = Math.max(0, size - nearCap);
        int midStart = Math.max(0, nearStart - midCap);
        List<List<SessionTurn>> nearRounds = rounds.subList(nearStart, size);
        List<List<SessionTurn>> midRounds = rounds.subList(midStart, nearStart);

        List<L1WindowRowView> rows = new ArrayList<>();
        appendBand(rows, "near", nearRounds, timesByMsgId, midAnswers, false);
        appendBand(rows, "mid", midRounds, timesByMsgId, midAnswers, true);
        if (StringUtils.hasText(farSummary)) {
            rows.add(new L1WindowRowView(
                    "far",
                    1,
                    null,
                    farSummary.strip(),
                    true,
                    farAt));
        }
        return List.copyOf(rows);
    }

    private static void appendBand(
            List<L1WindowRowView> rows,
            String band,
            List<List<SessionTurn>> rounds,
            Map<String, Instant> timesByMsgId,
            Map<String, String> midAnswers,
            boolean useMidSummary) {
        if (rounds == null || rounds.isEmpty()) {
            return;
        }
        // 区内新→旧
        List<List<SessionTurn>> newestFirst = new ArrayList<>(rounds);
        Collections.reverse(newestFirst);
        int index = 1;
        for (List<SessionTurn> round : newestFirst) {
            rows.add(toRow(band, index++, round, timesByMsgId, midAnswers, useMidSummary));
        }
    }

    private static L1WindowRowView toRow(
            String band,
            int index,
            List<SessionTurn> round,
            Map<String, Instant> timesByMsgId,
            Map<String, String> midAnswers,
            boolean useMidSummary) {
        String userText = null;
        String userMsgId = null;
        String assistantText = null;
        String assistantMsgId = null;
        for (SessionTurn turn : round) {
            if (turn == null || !StringUtils.hasText(turn.role())) {
                continue;
            }
            if ("user".equals(turn.role())) {
                if (userText == null) {
                    userText = turn.content();
                    userMsgId = turn.messageId();
                }
            } else if ("assistant".equals(turn.role())) {
                assistantText = turn.content();
                assistantMsgId = turn.messageId();
            }
        }
        boolean summarized = false;
        if (useMidSummary
                && StringUtils.hasText(assistantMsgId)
                && midAnswers != null
                && midAnswers.containsKey(assistantMsgId)) {
            String summary = midAnswers.get(assistantMsgId);
            if (StringUtils.hasText(summary)) {
                assistantText = summary;
                summarized = true;
            }
        }
        Instant at = null;
        if (timesByMsgId != null) {
            if (StringUtils.hasText(assistantMsgId)) {
                at = timesByMsgId.get(assistantMsgId);
            }
            if (at == null && StringUtils.hasText(userMsgId)) {
                at = timesByMsgId.get(userMsgId);
            }
        }
        return new L1WindowRowView(band, index, userText, assistantText, summarized, at);
    }
}
