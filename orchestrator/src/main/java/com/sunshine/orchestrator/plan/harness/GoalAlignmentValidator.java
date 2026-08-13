package com.sunshine.orchestrator.plan.harness;

import java.util.List;

/**
 * harness 目标对齐机械校验（无 LLM）。
 * STUCK 优先于 DEVIATED；DEVIATED 触发重规划，STUCK 触发强制综合回答。
 */
public final class GoalAlignmentValidator {
    /** 连续多少轮 roundGoalCompletion 单调下降视为偏离（需至少 3 轮样本才可信）。 */
    private static final int CONSECUTIVE_DECLINE_STEPS = 2;
    /** 自判完成度长期低于此值且已有产出，说明计划与目标脱节。 */
    private static final double LOW_GOAL_COMPLETION_THRESHOLD = 0.1;
    /** 至少完成 1 个 task 才判定低完成度偏离，避免首轮冷启动误报。 */
    private static final int MIN_COMPLETED_TASKS_FOR_DEVIATION = 1;

    public enum Alignment {
        OK,
        DEVIATED,
        STUCK
    }

    private final int staleRoundsThreshold;

    public GoalAlignmentValidator(int staleRoundsThreshold) {
        this.staleRoundsThreshold = staleRoundsThreshold;
    }

    public Alignment assess(PlanNotebook notebook) {
        if (notebook.getStaleRounds() >= staleRoundsThreshold) {
            return Alignment.STUCK;
        }
        if (hasConsecutiveRoundCompletionDecline(notebook.getRounds())
                || hasLongTermLowCompletion(notebook)) {
            return Alignment.DEVIATED;
        }
        return Alignment.OK;
    }

    private static boolean hasConsecutiveRoundCompletionDecline(List<RoundRecord> rounds) {
        if (rounds == null || rounds.size() < CONSECUTIVE_DECLINE_STEPS + 1) {
            return false;
        }
        int declineSteps = 0;
        for (int i = 1; i < rounds.size(); i++) {
            double prev = rounds.get(i - 1).roundGoalCompletion();
            double curr = rounds.get(i).roundGoalCompletion();
            if (curr < prev) {
                declineSteps++;
                if (declineSteps >= CONSECUTIVE_DECLINE_STEPS) {
                    return true;
                }
            } else {
                declineSteps = 0;
            }
        }
        return false;
    }

    private static boolean hasLongTermLowCompletion(PlanNotebook notebook) {
        return notebook.getTotalTasksCompleted() >= MIN_COMPLETED_TASKS_FOR_DEVIATION
                && notebook.getGoalCompletion() < LOW_GOAL_COMPLETION_THRESHOLD;
    }
}
