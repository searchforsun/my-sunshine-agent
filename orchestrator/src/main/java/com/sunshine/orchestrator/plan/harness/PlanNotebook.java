package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * H1 跨轮共享工作记忆（rebuild §5.0）。
 * 与 AgentScope {@code HarnessAgent} 无关，package {@code plan.harness} 为 Planner-Executor 内核专用。
 */
@Getter
public class PlanNotebook {
    private final String originalGoal;
    private final String userQuery;
    @Setter
    private String scene;
    private final Deque<TaskItem> taskQueue;
    private final List<RoundRecord> rounds;
    @Setter
    private double goalCompletion;
    @Setter
    private String nextDirection;
    @JsonSerialize(using = InstantIsoSerializer.class)
    private final Instant createdAt;
    private final int maxRounds;
    private final int maxTotalTasks;
    @Setter
    private int currentRound;
    @Setter
    private int totalTasksCompleted;
    @Setter
    private int staleRounds;
    @Setter
    private int replanCount;
    @Setter
    private String sessionId;

    private PlanNotebook(String originalGoal, String userQuery, String scene, int maxRounds, int maxTotalTasks) {
        this.originalGoal = originalGoal;
        this.userQuery = userQuery;
        this.scene = scene;
        this.taskQueue = new ArrayDeque<>();
        this.rounds = new ArrayList<>();
        this.createdAt = Instant.now();
        this.maxRounds = maxRounds;
        this.maxTotalTasks = maxTotalTasks;
    }

    public static PlanNotebook create(String originalGoal, String userQuery, String scene, int maxRounds, int maxTotalTasks) {
        return new PlanNotebook(originalGoal, userQuery, scene, maxRounds, maxTotalTasks);
    }

    public void appendRound(RoundRecord round) {
        rounds.add(round);
    }

    /** goal + taskQueue 摘要 + 近 N 轮 rounds；超阈时最老轮折叠为单行摘要（确定性截断，LLM 折叠留 H-4）。 */
    public String renderForPlanner(int nearKeepRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Goal\n").append(originalGoal).append('\n');
        if (userQuery != null && !userQuery.isBlank()) {
            sb.append("Query: ").append(userQuery).append('\n');
        }
        sb.append("Scene: ").append(scene).append('\n');
        sb.append("Progress: ").append(goalCompletion);
        if (nextDirection != null && !nextDirection.isBlank()) {
            sb.append(" | Next: ").append(nextDirection);
        }
        sb.append("\n\n## Task Queue\n");
        if (taskQueue.isEmpty()) {
            sb.append("(empty)\n");
        } else {
            for (TaskItem item : taskQueue) {
                sb.append("- ").append(item.taskId())
                        .append(" [").append(item.status()).append("] ")
                        .append(item.label()).append('\n');
            }
        }
        sb.append("\n## Rounds\n");
        int foldCount = Math.max(0, rounds.size() - nearKeepRounds);
        if (foldCount > 0) {
            sb.append("[folded] ").append(foldCount).append(" oldest round(s), indices 0-")
                    .append(foldCount - 1).append('\n');
        }
        for (int i = foldCount; i < rounds.size(); i++) {
            appendRoundDetail(sb, rounds.get(i));
        }
        return sb.toString();
    }

    static final class InstantIsoSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toString());
        }
    }

    private static void appendRoundDetail(StringBuilder sb, RoundRecord round) {
        sb.append("### R").append(round.roundIndex()).append('\n');
        if (round.task() != null) {
            sb.append("task=").append(round.task().taskId())
                    .append(" assess=").append(round.assessReason()).append('\n');
        }
        sb.append("completion=").append(round.roundGoalCompletion()).append('\n');
        for (NodeResult nodeResult : round.nodeResults()) {
            sb.append("- ").append(nodeResult.nodeId())
                    .append(" [").append(nodeResult.status()).append("] ")
                    .append(nodeResult.summary()).append('\n');
        }
    }
}
