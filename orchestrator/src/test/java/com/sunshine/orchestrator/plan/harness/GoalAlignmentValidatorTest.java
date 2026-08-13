package com.sunshine.orchestrator.plan.harness;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GoalAlignmentValidatorTest {
    @Test
    void okWhenHealthy() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setGoalCompletion(0.6);
        nb.setStaleRounds(0);
        assertThat(new GoalAlignmentValidator(3).assess(nb)).isEqualTo(GoalAlignmentValidator.Alignment.OK);
    }

    @Test
    void stuckWhenStaleRoundsReachThreshold() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setStaleRounds(3);
        assertThat(new GoalAlignmentValidator(3).assess(nb)).isEqualTo(GoalAlignmentValidator.Alignment.STUCK);
    }

    @Test
    void stuckTakesPriorityOverDeviated() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setStaleRounds(3);
        nb.setGoalCompletion(0.05);
        nb.setTotalTasksCompleted(2);
        assertThat(new GoalAlignmentValidator(3).assess(nb)).isEqualTo(GoalAlignmentValidator.Alignment.STUCK);
    }

    @Test
    void deviatedWhenConsecutiveRoundCompletionDeclines() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.appendRound(new RoundRecord(0, null, List.of(), 0.5, "ok"));
        nb.appendRound(new RoundRecord(1, null, List.of(), 0.4, "ok"));
        nb.appendRound(new RoundRecord(2, null, List.of(), 0.3, "ok"));
        assertThat(new GoalAlignmentValidator(3).assess(nb)).isEqualTo(GoalAlignmentValidator.Alignment.DEVIATED);
    }

    @Test
    void deviatedWhenLowCompletionWithCompletedTasks() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setGoalCompletion(0.05);
        nb.setTotalTasksCompleted(1);
        assertThat(new GoalAlignmentValidator(3).assess(nb)).isEqualTo(GoalAlignmentValidator.Alignment.DEVIATED);
    }
}
