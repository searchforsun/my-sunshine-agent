package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.plan.harness.WorkerDispatchTool;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import com.sunshine.orchestrator.taskboard.TodoTasksBridge;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 4.7.7 L2 目标对齐（spec §4）：onReasoning 每 N 轮把「原始用户问题 + 任务清单进度」重新摆回
 * 模型面前，要求 think 先对齐目标再行动，防 compaction 后漂移、收集无关素材不自知。
 *
 * <p>瞬态注入模式完全复用 AS 2.0 原生 {@code TaskReminderMiddleware}：USER + {@code system-reminder}
 * + METADATA_SYNTHETIC + {@code agentscope_reminder_kind=goal_check}，追加到 ReasoningInput.messages
 * 末尾，不落 AgentState.context、不参与 compaction。
 *
 * <p>无状态单例（P2-1 E5）：注入轮次与工具闸门集中在 {@link AgentRunState}。
 * 与 {@link FailureBudgetMiddleware} 同链时排在其前：onReasoning 注入顺序 goal-check → budget。
 */
@Slf4j
public class GoalAlignmentMiddleware implements MiddlewareBase {

    private static final String REACT_GOAL_CHECK_CATALOG_ID = "react.goal-check";
    private static final String REMINDER_KIND = "goal_check";

    private final AgentExecutionProperties executionProperties;
    private final PromptCatalogHolder catalogHolder;

    public GoalAlignmentMiddleware(
            AgentExecutionProperties executionProperties,
            PromptCatalogHolder catalogHolder) {
        this.executionProperties = executionProperties;
        this.catalogHolder = catalogHolder;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(maybeInjectGoalCheck(agent, ctx, input));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        // 业务工具完成 → 标记工具闸门（goal-check 触发条件 5，与失败预算状态独立）
        return next.apply(input).doOnNext(ev -> {
            if (ev instanceof ToolResultEndEvent end) {
                String toolName = end.getToolCallName();
                if (toolName != null && !isMetaTool(toolName)) {
                    AgentRunState state = runStateOf(ctx);
                    if (state != null) {
                        state.markToolDone();
                    }
                }
            }
        });
    }

    /** 触发条件全部满足才注入（spec §4.1）；不满足时原样透传 */
    private ReasoningInput maybeInjectGoalCheck(Agent agent, RuntimeContext ctx, ReasoningInput input) {
        AgentExecutionProperties.React.GoalCheck cfg = goalCheckConfig();
        if (!cfg.isEnabled()) {
            return input;
        }
        if (!isMain(ctx)) {
            return input;
        }
        if (input == null || input.messages() == null || input.messages().isEmpty()) {
            return input;
        }
        // 条件 3：模型已建板（tasksContext 非空）；未建板的简单对话不打扰
        List<TaskBoardItemView> items = TodoTasksBridge.currentItems(agent, ctx);
        if (items.isEmpty()) {
            return input;
        }
        String bridgeId = bridgeIdOf(ctx);
        if (bridgeId == null) {
            return input;
        }
        AgentRunState state = StepEventBridge.runState(bridgeId);
        if (state == null) {
            return input;
        }
        // 条件 4：当前 reasoning 轮次满足 iter % every-n-think == 0
        int iter = state.nextReasoningIter();
        if (cfg.getEveryNThink() <= 0 || iter % cfg.getEveryNThink() != 0) {
            return input;
        }
        // 条件 5：距上次注入后至少发生过 1 次业务工具完成（连续纯 think 不重复轰炸）
        int last = state.goalCheckLastInjectedIter();
        if (last > 0 && !state.toolDoneSinceLastInject()) {
            return input;
        }
        String template = catalogInstruction(REACT_GOAL_CHECK_CATALOG_ID);
        if (!StringUtils.hasText(template)) {
            log.warn("[GoalAlignment] catalog missing id={}", REACT_GOAL_CHECK_CATALOG_ID);
            return input;
        }
        Object rawQuery = ctx != null ? ctx.get(ProcessingStepMiddleware.CTX_USER_QUERY) : null;
        String userQuery = rawQuery != null ? rawQuery.toString() : "";
        String text = template
                .replace("{userQuery}", userQuery)
                .replace("{taskProgress}", renderProgress(items));
        log.info("[GoalAlignment] goal-check 注入 bridgeId={} iter={}", bridgeId, iter);
        List<Msg> messages = new ArrayList<>(input.messages().size() + 1);
        messages.addAll(input.messages());
        messages.add(buildReminder(text, REMINDER_KIND));
        state.markGoalCheckInjected(iter);
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    /** 任务进度渲染：{@code 2/5 已完成 · 进行中：…}（复用 TaskBoard 映射语义，spec §4.2） */
    static String renderProgress(List<TaskBoardItemView> items) {
        if (items == null || items.isEmpty()) {
            return "0/0 已完成";
        }
        long completed = items.stream().filter(i -> "completed".equals(i.status())).count();
        String inProgress = items.stream()
                .filter(i -> "in_progress".equals(i.status()))
                .map(TaskBoardItemView::content)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        StringBuilder sb = new StringBuilder(completed + "/" + items.size() + " 已完成");
        if (inProgress != null) {
            sb.append(" · 进行中：").append(inProgress.strip());
        }
        return sb.toString();
    }

    /** 元工具/状态工具不上目标对齐的「业务工具完成」闸门 */
    private boolean isMetaTool(String toolName) {
        return TodoTasksBridge.isTodoWrite(toolName)
                || SpawnSubagentTool.NAME.equals(toolName)
                || RequestDecisionTool.NAME.equals(toolName)
                || WorkerDispatchTool.NAME.equals(toolName);
    }

    private static Msg buildReminder(String text, String reminderKind) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(text).build())
                .metadata(Map.of(
                        "agentscope_synthetic", Boolean.TRUE,
                        "agentscope_reminder_kind", reminderKind))
                .build();
    }

    private boolean isMain(RuntimeContext ctx) {
        return ctx != null && ctx.get(ProcessingStepMiddleware.CTX_AGENT_ROLE) == AgentRole.MAIN;
    }

    private AgentRunState runStateOf(RuntimeContext ctx) {
        String bridgeId = bridgeIdOf(ctx);
        return bridgeId != null ? StepEventBridge.runState(bridgeId) : null;
    }

    private static String bridgeIdOf(RuntimeContext ctx) {
        return ctx != null ? ctx.get(ProcessingStepMiddleware.CTX_BRIDGE_ID) : null;
    }

    private AgentExecutionProperties.React.GoalCheck goalCheckConfig() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getGoalCheck() != null
                ? react.getGoalCheck()
                : new AgentExecutionProperties.React.GoalCheck();
    }

    private String catalogInstruction(String id) {
        return catalogHolder.snapshot().entry(id)
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElse("");
    }
}
