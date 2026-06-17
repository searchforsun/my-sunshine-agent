package com.sunshine.orchestrator.memory.stm;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * STM 滑动窗口：任意请求均保留尾部窗口并裁剪字符（不再依赖「继续」等延续词）。
 */
public final class StmWindowPolicy {

    private StmWindowPolicy() {
    }

    public static List<ChatTurn> selectWindow(List<ChatTurn> loadedHistory, MemoryProperties.Stm stm) {
        if (loadedHistory == null || loadedHistory.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(2, stm.getMaxMessages());
        List<ChatTurn> tail = loadedHistory.size() <= limit
                ? new ArrayList<>(loadedHistory)
                : new ArrayList<>(loadedHistory.subList(loadedHistory.size() - limit, loadedHistory.size()));
        return trimByChars(tail, stm.getMaxChars());
    }

    static List<ChatTurn> trimByChars(List<ChatTurn> turns, int maxChars) {
        if (maxChars <= 0 || turns.isEmpty()) {
            return turns;
        }
        int total = turns.stream().mapToInt(t -> t.content() != null ? t.content().length() : 0).sum();
        if (total <= maxChars) {
            return List.copyOf(turns);
        }
        List<ChatTurn> out = new ArrayList<>(turns);
        while (!out.isEmpty() && total > maxChars) {
            ChatTurn removed = out.remove(0);
            total -= removed.content() != null ? removed.content().length() : 0;
        }
        return out;
    }
}
