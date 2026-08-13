package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * H1 跨轮共享工作记忆（rebuild §5.0）。
 * 与 AgentScope {@code HarnessAgent} 无关，package {@code plan.harness} 为 Planner-Executor 内核专用。
 */
@Getter
public class PlanNotebook {
    /** follow-up 目标变更可写（S6 ③） */
    @Setter
    private String originalGoal;
    @Setter
    private String userQuery;
    /** 会话形态 chat/task（routing 四轴 `kind`；JSON 过渡可读旧键 scene） */
    @Setter
    private String kind;
    private final Deque<TaskItem> taskQueue;
    private final List<RoundRecord> rounds;
    @Setter
    private double goalCompletion;
    @Setter
    private String nextDirection;
    @JsonSerialize(using = InstantIsoSerializer.class)
    @JsonDeserialize(using = InstantIsoDeserializer.class)
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

    private PlanNotebook(String originalGoal, String userQuery, String kind, int maxRounds, int maxTotalTasks) {
        this(originalGoal, userQuery, kind, null, null, 0.0, null, Instant.now(),
                maxRounds, maxTotalTasks, 0, 0, 0, 0, null);
    }

    @JsonCreator
    private PlanNotebook(
            @JsonProperty("originalGoal") String originalGoal,
            @JsonProperty("userQuery") String userQuery,
            @JsonProperty("kind") @JsonAlias("scene") String kind,
            @JsonProperty("taskQueue") Collection<TaskItem> taskQueue,
            @JsonProperty("rounds") List<RoundRecord> rounds,
            @JsonProperty("goalCompletion") double goalCompletion,
            @JsonProperty("nextDirection") String nextDirection,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("maxRounds") int maxRounds,
            @JsonProperty("maxTotalTasks") int maxTotalTasks,
            @JsonProperty("currentRound") int currentRound,
            @JsonProperty("totalTasksCompleted") int totalTasksCompleted,
            @JsonProperty("staleRounds") int staleRounds,
            @JsonProperty("replanCount") int replanCount,
            @JsonProperty("sessionId") String sessionId) {
        this.originalGoal = originalGoal;
        this.userQuery = userQuery;
        this.kind = kind;
        this.taskQueue = taskQueue != null ? new ArrayDeque<>(taskQueue) : new ArrayDeque<>();
        this.rounds = rounds != null ? new ArrayList<>(rounds) : new ArrayList<>();
        this.goalCompletion = goalCompletion;
        this.nextDirection = nextDirection;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.maxRounds = maxRounds;
        this.maxTotalTasks = maxTotalTasks;
        this.currentRound = currentRound;
        this.totalTasksCompleted = totalTasksCompleted;
        this.staleRounds = staleRounds;
        this.replanCount = replanCount;
        this.sessionId = sessionId;
    }

    public static PlanNotebook create(String originalGoal, String userQuery, String kind, int maxRounds, int maxTotalTasks) {
        return new PlanNotebook(originalGoal, userQuery, kind, maxRounds, maxTotalTasks);
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
        sb.append("Kind: ").append(kind).append('\n');
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

    static final class InstantIsoDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return Instant.parse(p.getText());
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
