package com.sunshine.orchestrator.processing;

/** Agent 路径下分析/生成步骤耗时分配（ReAct 常一次性吐出正文） */
public final class StepDurationFinalizer {

    private static final long MIN_GENERATE_MS = 400L;
    private static final long MAX_GENERATE_MS = 8_000L;
    private static final long MS_PER_CHAR = 5L;
    private static final long MIN_AGENT_MS = 300L;

    private StepDurationFinalizer() {
    }

    /**
     * 在 agent 阶段总墙钟时间内，为「生成回答」分配合理耗时（按正文字数估算，保持总时长不变）
     */
    public static long allocateGenerateMs(int contentChars, long agentWallMs) {
        if (agentWallMs <= MIN_AGENT_MS + MIN_GENERATE_MS) {
            return Math.max(MIN_GENERATE_MS, agentWallMs / 2);
        }
        if (contentChars <= 0) {
            return MIN_GENERATE_MS;
        }
        long byContent = contentChars * MS_PER_CHAR;
        long capped = Math.min(Math.max(byContent, MIN_GENERATE_MS), MAX_GENERATE_MS);
        long maxAllowed = agentWallMs - MIN_AGENT_MS;
        return Math.min(capped, maxAllowed);
    }

    public static long agentEndAt(long agentStartAt, long phaseEndAt, int contentChars) {
        long wall = Math.max(0, phaseEndAt - agentStartAt);
        long generateMs = allocateGenerateMs(contentChars, wall);
        long agentEnd = agentStartAt + (wall - generateMs);
        return Math.max(agentStartAt + MIN_AGENT_MS, agentEnd);
    }
}
