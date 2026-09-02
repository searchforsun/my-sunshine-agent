package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.plan.harness.WorkerDispatchTool;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.taskboard.TodoTasksBridge;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 4.7.7 L3 失败预算（spec §5）：onActing 从 {@link ToolResultEndEvent#getState()} 判定失败
 * （复用 4.5.7 契约，禁止正文关键字猜测），达阈值向**下一轮** ReasoningInput 注入瞬态强提示，
 * 防止同参数死循环到 max-iters 耗尽。INTERRUPTED（用户取消）不计数——已属 4.5.7 硬拒路径。
 *
 * <p>无状态单例（P2-1 E5）：per-run 计数与触发标记集中在 {@link AgentRunState}
 * （挂 {@link StepEventBridge} bridgeId 生命周期，clear 随 bridge 回收）。
 * 与 {@link GoalAlignmentMiddleware} 同链时：onReasoning 排在 goal-check 之后注入（spec §4.3），
 * onActing 事件流中先于 {@link ProcessingStepMiddleware} 收到 ToolResultEndEvent（链序
 * GoalAlignment → ProcessingStep → FailureBudget，MiddlewareChain 洋葱序下 FBM 最内层），
 * 保证 budget 标记先于 PSM 的 completeToolStep 文案查询（spec §5.4）。
 */
@Slf4j
public class FailureBudgetMiddleware implements MiddlewareBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REACT_TOOL_FAILURE_BUDGET_CATALOG_ID = "react.tool-failure-budget";
    private static final String REMINDER_KIND = "tool_failure_budget";
    private static final int LAST_ERROR_LEN = 200;

    private final AgentExecutionProperties executionProperties;
    private final PromptCatalogHolder catalogHolder;

    public FailureBudgetMiddleware(
            AgentExecutionProperties executionProperties,
            PromptCatalogHolder catalogHolder) {
        this.executionProperties = executionProperties;
        this.catalogHolder = catalogHolder;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        // 入口：登记 signature（toolUseId → toolName#sha1(规范化 input JSON)），End 事件回查
        Map<String, String> signatureByToolUse = new ConcurrentHashMap<>();
        Map<String, StringBuilder> resultTextById = new ConcurrentHashMap<>();
        for (ToolUseBlock tu : input.toolCalls()) {
            String id = tu.getId();
            if (id != null && !isExcluded(tu.getName())) {
                signatureByToolUse.put(id, signatureOf(tu.getName(), tu.getInput()));
            }
        }
        return next.apply(input).doOnNext(ev -> {
            if (ev instanceof ToolResultTextDeltaEvent d) {
                resultTextById.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder())
                        .append(d.getDelta());
            } else if (ev instanceof ToolResultEndEvent end) {
                String toolUseId = end.getToolCallId();
                StringBuilder acc = resultTextById.remove(toolUseId);
                String lastError = acc != null ? firstLine(acc.toString()) : null;
                handleToolEnd(ctx, end, signatureByToolUse.get(toolUseId), lastError);
            }
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(maybeInjectBudgetWarning(ctx, input));
    }

    /** 失败判定（spec §5.1）：ERROR 计数；SUCCESS 清零；INTERRUPTED/DENIED/RUNNING 不计 */
    private void handleToolEnd(RuntimeContext ctx, ToolResultEndEvent end, String signature, String lastError) {
        AgentExecutionProperties.React.ToolFailureBudget cfg = budgetConfig();
        if (!cfg.isEnabled()) {
            return;
        }
        String toolName = end.getToolCallName();
        if (toolName == null || isExcluded(toolName)) {
            return;
        }
        AgentRunState state = runStateOf(ctx);
        if (state == null) {
            return;
        }
        ToolResultState s = end.getState();
        if (s == ToolResultState.SUCCESS) {
            // 成功清零：同参数成功 → signature 与 toolName 双维度都清零
            state.resetFailure(toolName);
            if (signature != null) {
                state.resetFailure(signature);
            }
            return;
        }
        if (s != ToolResultState.ERROR) {
            return;
        }
        String toolUseId = end.getToolCallId();
        if (signature != null) {
            state.recordFailure(signature, cfg.getSameSignatureMax(), toolName, lastError, toolUseId);
        }
        state.recordFailure(toolName, cfg.getPerToolMax(), toolName, lastError, toolUseId);
    }

    /** 下一轮 reasoning：有待注入的预算强提示 → 追加瞬态 USER 提醒（一次性，注入后清空） */
    private ReasoningInput maybeInjectBudgetWarning(RuntimeContext ctx, ReasoningInput input) {
        AgentExecutionProperties.React.ToolFailureBudget cfg = budgetConfig();
        if (!cfg.isEnabled() || input == null || input.messages() == null || input.messages().isEmpty()) {
            return input;
        }
        String bridgeId = bridgeIdOf(ctx);
        if (bridgeId == null) {
            return input;
        }
        AgentRunState state = StepEventBridge.runState(bridgeId);
        if (state == null || !state.hasPendingBudgetInjection()) {
            return input;
        }
        String template = catalogInstruction(REACT_TOOL_FAILURE_BUDGET_CATALOG_ID);
        if (!StringUtils.hasText(template)) {
            log.warn("[FailureBudget] catalog missing id={}", REACT_TOOL_FAILURE_BUDGET_CATALOG_ID);
            state.drainPendingBudgetInjections();
            return input;
        }
        StringBuilder sb = new StringBuilder();
        for (AgentRunState.PendingBudget p : state.drainPendingBudgetInjections()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(template
                    .replace("{toolName}", p.toolName() != null ? p.toolName() : "")
                    .replace("{failCount}", String.valueOf(p.failCount()))
                    .replace("{lastError}", StringUtils.hasText(p.lastError()) ? p.lastError() : "未知"));
        }
        log.info("[FailureBudget] budget 达阈值注入强提示 bridgeId={}", bridgeId);
        List<Msg> messages = new ArrayList<>(input.messages().size() + 1);
        messages.addAll(input.messages());
        messages.add(buildReminder(sb.toString(), REMINDER_KIND));
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    /** 同参数指纹：toolName + sha1(key 排序后的 input JSON)；不做 value 语义归一（避免误合并） */
    private String signatureOf(String toolName, Map<String, Object> input) {
        return toolName + "#" + DigestUtils.md5DigestAsHex(normalizeInput(input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String normalizeInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        TreeMap<String, Object> sorted = new TreeMap<>(input);
        try {
            return MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            return String.valueOf(sorted);
        }
    }

    private static String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String t = text.strip();
        int nl = t.indexOf('\n');
        String line = nl >= 0 ? t.substring(0, nl) : t;
        if (line.length() > LAST_ERROR_LEN) {
            line = line.substring(0, LAST_ERROR_LEN) + "…";
        }
        return line;
    }

    /** 排除状态/元工具（spec §5.1）：不占失败预算 */
    private boolean isExcluded(String toolName) {
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

    private AgentRunState runStateOf(RuntimeContext ctx) {
        String bridgeId = bridgeIdOf(ctx);
        return bridgeId != null ? StepEventBridge.runState(bridgeId) : null;
    }

    private static String bridgeIdOf(RuntimeContext ctx) {
        return ctx != null ? ctx.get(ProcessingStepMiddleware.CTX_BRIDGE_ID) : null;
    }

    private AgentExecutionProperties.React.ToolFailureBudget budgetConfig() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getToolFailureBudget() != null
                ? react.getToolFailureBudget()
                : new AgentExecutionProperties.React.ToolFailureBudget();
    }

    private String catalogInstruction(String id) {
        return catalogHolder.snapshot().entry(id)
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElse("");
    }
}
