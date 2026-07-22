package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.context.l3.L3RecallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨轮上下文读路径：L2 system + L1 Near/Mid/Far + L3 材料（含 Far 回填）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private final ContextProperties contextProperties;
    private final ConversationContextL1Store l1Store;
    private final L2StateStore l2StateStore;
    private final L3RecallService l3RecallService;

    public AssembledContext assemble(AssembleRequest request) {
        if (!contextProperties.isEnabled()) {
            return AssembledContext.empty();
        }
        List<SessionTurn> source = sanitizeTurns(request.history());
        ContextProperties.L1 l1 = contextProperties.getL1();
        int nearN = Math.max(1, l1.getNearTurns());
        int midN = Math.max(0, l1.getMidTurns());
        L1Compressor.WindowBands bands = L1Compressor.partition(source, nearN, midN);

        ConversationContextL1Entity entity = l1Store.find(request.conversationId()).orElse(null);
        Map<String, String> midAnswers = l1Store.parseMidAnswers(entity);
        String farSummary = l1Store.farSummaryOf(entity);

        List<ChatTurn> mid = projectMid(bands.mid(), midAnswers);
        List<ChatTurn> near = toChatTurns(trimByChars(bands.near(), l1.getMaxChars()));
        String farBlock = StringUtils.hasText(farSummary) ? farSummary.strip() : "";
        String l2Block = l2StateStore.assembleSystemBlock(request.userId(), request.tenantId());

        Set<String> nearMidIds = collectMsgIds(bands.near(), bands.mid());
        Set<String> farIds = collectMsgIds(bands.far());
        String l3Block = "";
        if (StringUtils.hasText(request.currentUserQuery())) {
            try {
                l3Block = l3RecallService.recall(
                        request.userId(),
                        request.tenantId(),
                        request.currentUserQuery(),
                        nearMidIds,
                        farIds,
                        StringUtils.hasText(farBlock));
            } catch (Exception e) {
                log.warn("[Context] L3 recall 失败 conv={}: {}", request.conversationId(), e.getMessage());
            }
        }

        AssembledContext assembled = new AssembledContext(
                l2Block != null ? l2Block : "",
                farBlock,
                mid,
                near,
                l3Block != null ? l3Block : "");
        AssembledContext trimmed = applyBudget(assembled, l1.getMaxChars());

        log.debug("[Context] assemble conv={} l2={} far={} mid={} near={} l3={}",
                request.conversationId(),
                trimmed.l2SystemBlock().isBlank() ? 0 : 1,
                trimmed.farSummaryBlock().isBlank() ? 0 : 1,
                trimmed.midTurns().size(),
                trimmed.nearTurns().size(),
                trimmed.l3MaterialBlock().isBlank() ? 0 : 1);
        return trimmed;
    }

    /**
     * 预算裁剪：先丢 L3 → 再丢 Far → Mid 从头整轮丢弃 → 永不丢 L2（含 constraint 行）。
     * Near 已在组装路径 trimByChars；此处在 L3/Far 仍超预算时再裁 Mid。
     */
    static AssembledContext applyBudget(AssembledContext ctx, int maxChars) {
        if (ctx == null) {
            return AssembledContext.empty();
        }
        if (maxChars <= 0) {
            return ctx;
        }
        if (estimateChars(ctx) <= maxChars) {
            return ctx;
        }
        AssembledContext dropL3 = new AssembledContext(
                ctx.l2SystemBlock(),
                ctx.farSummaryBlock(),
                ctx.midTurns(),
                ctx.nearTurns(),
                "");
        if (estimateChars(dropL3) <= maxChars) {
            return dropL3;
        }
        AssembledContext dropFar = new AssembledContext(
                ctx.l2SystemBlock(),
                "",
                ctx.midTurns(),
                ctx.nearTurns(),
                "");
        if (estimateChars(dropFar) <= maxChars) {
            return dropFar;
        }
        List<ChatTurn> mid = ctx.midTurns() != null
                ? new ArrayList<>(ctx.midTurns())
                : new ArrayList<>();
        while (!mid.isEmpty() && estimateChars(new AssembledContext(
                ctx.l2SystemBlock(), "", mid, ctx.nearTurns(), "")) > maxChars) {
            mid.remove(0);
        }
        return new AssembledContext(
                ctx.l2SystemBlock(),
                "",
                List.copyOf(mid),
                ctx.nearTurns(),
                "");
    }

    static int estimateChars(AssembledContext ctx) {
        if (ctx == null) {
            return 0;
        }
        int n = len(ctx.l2SystemBlock()) + len(ctx.farSummaryBlock()) + len(ctx.l3MaterialBlock());
        n += turnsChars(ctx.midTurns());
        n += turnsChars(ctx.nearTurns());
        return n;
    }

    private static int turnsChars(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (ChatTurn t : turns) {
            if (t != null && t.content() != null) {
                n += t.content().length();
            }
        }
        return n;
    }

    private static int len(String s) {
        return s != null ? s.length() : 0;
    }

    @SafeVarargs
    static Set<String> collectMsgIds(List<SessionTurn>... bands) {
        Set<String> ids = new HashSet<>();
        if (bands == null) {
            return ids;
        }
        for (List<SessionTurn> band : bands) {
            if (band == null) {
                continue;
            }
            for (SessionTurn t : band) {
                if (t != null && StringUtils.hasText(t.messageId())) {
                    ids.add(t.messageId());
                }
            }
        }
        return ids;
    }

    static List<ChatTurn> projectMid(List<SessionTurn> midBand, Map<String, String> midAnswers) {
        if (midBand == null || midBand.isEmpty()) {
            return List.of();
        }
        Map<String, String> answers = midAnswers != null ? midAnswers : Map.of();
        List<ChatTurn> out = new ArrayList<>(midBand.size());
        for (SessionTurn turn : midBand) {
            if ("assistant".equals(turn.role())
                    && StringUtils.hasText(turn.messageId())
                    && answers.containsKey(turn.messageId())) {
                out.add(new ChatTurn(turn.role(), answers.get(turn.messageId())));
            } else {
                out.add(turn.toChatTurn());
            }
        }
        return List.copyOf(out);
    }

    static List<ChatTurn> toChatTurns(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream().map(SessionTurn::toChatTurn).toList();
    }

    /** 超 maxChars 从头整条丢弃（不截断单条 content）；入参为 Near 带 SessionTurn。 */
    static List<SessionTurn> trimByChars(List<SessionTurn> turns, int maxChars) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        if (maxChars <= 0) {
            return List.copyOf(turns);
        }
        int total = turns.stream().mapToInt(t -> t.content() != null ? t.content().length() : 0).sum();
        if (total <= maxChars) {
            return List.copyOf(turns);
        }
        List<SessionTurn> out = new ArrayList<>(turns);
        while (!out.isEmpty() && total > maxChars) {
            SessionTurn removed = out.remove(0);
            total -= removed.content() != null ? removed.content().length() : 0;
        }
        return List.copyOf(out);
    }

    private static List<SessionTurn> sanitizeTurns(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream()
                .filter(t -> t.content() != null && !t.content().isBlank())
                .toList();
    }

    public record AssembleRequest(
            String userId,
            String tenantId,
            String conversationId,
            List<SessionTurn> history,
            String currentUserQuery
    ) {
    }
}
