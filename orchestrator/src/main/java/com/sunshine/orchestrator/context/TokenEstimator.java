package com.sunshine.orchestrator.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.sunshine.orchestrator.conversation.ChatTurn;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 真实 token 计量（jtokkit cl100k_base），替代原 String.length() 字符估算。
 * cl100k 对 deepseek/qwen 估算偏高 5-15%，经 effectiveCount 的 safetyFactor 保守系数提前触发。
 */
@Component
public class TokenEstimator {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /** 单段文本 token 数。 */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** ChatTurn 列表 token 数（仅 content 求和）。 */
    public int count(List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (ChatTurn t : turns) {
            if (t != null) {
                n += count(t.content());
            }
        }
        return n;
    }

    /** 组装上下文总 token：L2 + Far + L3 + Mid + Near。 */
    public int countAssembled(AssembledContext ctx) {
        if (ctx == null) {
            return 0;
        }
        return count(ctx.l2SystemBlock())
                + count(ctx.farSummaryBlock())
                + count(ctx.l3MaterialBlock())
                + count(ctx.midTurns())
                + count(ctx.nearTurns());
    }

    /** 会话历史 token × 保守系数（SessionTurn 仅 content 求和）。 */
    public int effectiveCount(List<SessionTurn> history, double safetyFactor) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int raw = 0;
        for (SessionTurn t : history) {
            if (t != null) {
                raw += count(t.content());
            }
        }
        return (int) Math.ceil(raw * safetyFactor);
    }
}
