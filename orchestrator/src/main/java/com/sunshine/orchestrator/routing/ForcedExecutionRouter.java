package com.sunshine.orchestrator.routing;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.AgentBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.workflow.WorkflowBindingOutcome;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 钉死 executionMode 的分轨资源收集：
 * 轨 A（FAST|PRO）skill/agent；轨 B（WORKFLOW）仅 workflowId；永不改 mode。
 * routingTraces 文案面向用户（处理方式 / 匹配 / 绑定结论），不暴露内部 layer 与 id。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForcedExecutionRouter {

    private static final String REASON_FAST = "user:forced-fast";
    private static final String REASON_PRO = "user:forced-pro";
    private static final String REASON_WORKFLOW = "user:forced-workflow";
    private static final String PARAM_AGENT_IDS = "agentIds";

    private final SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    private final AgentBindingRoutingPolicy agentBindingRoutingPolicy;
    private final PromptCatalogHolder promptCatalogHolder;
    private final IntentRouter intentRouter;
    private final WorkflowBindingRoutingPolicy workflowBindingRoutingPolicy;
    private final WorkflowBindingParser workflowBindingParser;
    private final SkillCatalogService skillCatalogService;
    private final AgentCatalogService agentCatalogService;
    private final WorkflowCatalog workflowCatalog;

    public Mono<ExecutionPlan> resolve(RoutingContext ctx, ExecutionPreference preference, String workflowId) {
        if (preference == null) {
            return Mono.error(new IllegalStateException("ForcedExecutionRouter 需要非空 preference"));
        }
        return switch (preference) {
            case FAST -> resolveForced(ctx, ExecutionMode.FAST, REASON_FAST);
            case PRO -> resolveForced(ctx, ExecutionMode.PRO, REASON_PRO);
            case WORKFLOW -> resolveWorkflowPinned(ctx, workflowId);
        };
    }

    /** WORKFLOW：先 #mention / 请求体 workflowId，再同轨规则与 L3；无候选显式失败 */
    private Mono<ExecutionPlan> resolveWorkflowPinned(RoutingContext ctx, String workflowId) {
        RoutingContext bindCtx = ctx;
        if (StringUtils.hasText(workflowId) && !StringUtils.hasText(ctx.forcedWorkflowId())) {
            bindCtx = ctx.withForcedWorkflowId(workflowId.strip());
        }
        final RoutingContext traceCtx = bindCtx;
        RoutingContext lockedCtx = bindCtx.withLockedMode(ExecutionMode.WORKFLOW);
        return workflowBindingRoutingPolicy.tryRoute(lockedCtx).flatMap(opt -> {
            if (opt.isPresent()) {
                return Mono.just(appendWorkflowL0Traces(opt.get(), traceCtx));
            }
            return resolveForced(ctx, ExecutionMode.WORKFLOW, REASON_WORKFLOW);
        });
    }

    /** #workflow 硬绑定：policy plan 不带 trace，这里补 mode/track/L0/final */
    private ExecutionPlan appendWorkflowL0Traces(ExecutionPlan plan, RoutingContext ctx) {
        List<RoutingTrace> traces = new ArrayList<>();
        traces.add(RoutingTrace.of("mode", "处理方式", modeTraceText(ExecutionMode.WORKFLOW)));
        traces.add(RoutingTrace.of("track", "匹配方式", trackTraceText(ExecutionMode.WORKFLOW)));
        if (StringUtils.hasText(plan.workflowId())) {
            traces.add(RoutingTrace.of("L0", "绑定流程",
                    "使用流程" + bracket(workflowDisplayName(plan.workflowId()))));
        }
        traces.add(RoutingTrace.of("final", "执行方案", finalBindingText(ExecutionMode.WORKFLOW,
                plan.workflowId(), Map.of())));
        return new ExecutionPlan(plan.mode(), plan.workflowId(), plan.params(), plan.reason(), plan.ruleId(),
                List.copyOf(traces));
    }

    private Mono<ExecutionPlan> resolveForced(RoutingContext ctx, ExecutionMode locked, String reason) {
        RoutingContext lockedCtx = ctx.withLockedMode(locked);
        if (locked == ExecutionMode.FAST || locked == ExecutionMode.PRO) {
            warnAndIgnoreHashWorkflow(lockedCtx);
        }
        BindingAcc acc = new BindingAcc(locked, reason);
        acc.trace("mode", "处理方式", modeTraceText(locked));
        acc.trace("track", "匹配方式", trackTraceText(locked));
        Mono<BindingAcc> pipeline = Mono.just(acc);
        if (lockedCtx.allowsSkillBinding()) {
            pipeline = pipeline.flatMap(a -> agentBindingRoutingPolicy.tryRoute(lockedCtx).map(opt -> {
                if (opt.isPresent()) {
                    ExecutionPlan agentPlan = opt.get();
                    a.mergeParamsFillGaps(agentPlan.params());
                    if (StringUtils.hasText(agentPlan.params().get(PARAM_AGENT_IDS))) {
                        a.trace("L0", "绑定助手",
                                "使用助手" + bracket(agentDisplayName(agentPlan.params().get(PARAM_AGENT_IDS))) + "处理");
                    }
                }
                return a;
            }));
            pipeline = pipeline.flatMap(a -> skillBindingRoutingPolicy.tryRoute(lockedCtx).map(opt -> {
                if (opt.isPresent()) {
                    ExecutionPlan skillPlan = opt.get();
                    a.mergeParamsFillGaps(skillPlan.params());
                    if (StringUtils.hasText(skillPlan.params().get(SkillBindingOutcome.PARAM_SKILL))) {
                        a.trace("L0", "绑定技能",
                                "使用技能" + bracket(skillDisplayName(skillPlan.params().get(SkillBindingOutcome.PARAM_SKILL))) + "处理");
                    }
                }
                return a;
            }));
        }
        return pipeline.flatMap(a -> {
            // L0 命中（任意 /skill 或任意 $agent）：跳过规则层与 L3，直接出方案
            if (a.hasL0Trigger()) {
                return Mono.just(a.toPlan());
            }
            applyTrackRule(lockedCtx.userMessage(), a);
            if (!a.needsL3()) {
                return Mono.just(a.toPlan());
            }
            return intentRouter.classifyPlan(lockedCtx)
                    .flatMap(llm -> {
                        a.mergeFromL3(IntentRouter.applyLockedMode(llm, locked));
                        if (locked == ExecutionMode.WORKFLOW && !StringUtils.hasText(a.workflowId)) {
                            return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_TEMPLATE_NOT_FOUND));
                        }
                        String l3Detail = describeL3Binding(a);
                        if (StringUtils.hasText(l3Detail)) {
                            a.trace("L3", "意图识别", l3Detail);
                        }
                        return Mono.just(a.toPlan());
                    });
        });
    }

    private String describeL3Binding(BindingAcc a) {
        if (a.locked == ExecutionMode.WORKFLOW) {
            return StringUtils.hasText(a.workflowId)
                    ? "识别为流程" + bracket(workflowDisplayName(a.workflowId))
                    : null;
        }
        String skill = a.params.get(SkillBindingOutcome.PARAM_SKILL);
        String agents = a.params.get(PARAM_AGENT_IDS);
        if (StringUtils.hasText(skill) || StringUtils.hasText(agents)) {
            return StringUtils.hasText(skill)
                    ? "识别出应使用技能" + bracket(skillDisplayName(skill))
                    : "识别出应使用助手" + bracket(agentDisplayName(agents));
        }
        return null;
    }

    private String finalBindingText(ExecutionMode locked, String workflowId, Map<String, String> params) {
        if (locked == ExecutionMode.WORKFLOW) {
            return StringUtils.hasText(workflowId)
                    ? "将执行" + bracket(workflowDisplayName(workflowId)) + "流程"
                    : "未匹配到可用流程";
        }
        String skill = params.get(SkillBindingOutcome.PARAM_SKILL);
        String agents = params.get(PARAM_AGENT_IDS);
        if (StringUtils.hasText(skill) && StringUtils.hasText(agents)) {
            return "使用技能" + bracket(skillDisplayName(skill))
                    + "与助手" + bracket(agentDisplayName(agents)) + "处理";
        }
        if (StringUtils.hasText(skill)) {
            return "使用技能" + bracket(skillDisplayName(skill)) + "处理";
        }
        if (StringUtils.hasText(agents)) {
            return "使用助手" + bracket(agentDisplayName(agents)) + "处理";
        }
        return locked == ExecutionMode.FAST ? "交由智能体自主分析作答" : "动态规划多步执行";
    }

    private static String modeDisplayName(ExecutionMode mode) {
        return switch (mode) {
            case FAST -> "快速";
            case PRO -> "专业";
            case WORKFLOW -> "工作流";
        };
    }

    private static String modeTraceText(ExecutionMode mode) {
        return "按您选择的「" + modeDisplayName(mode) + "」模式处理";
    }

    private static String trackTraceText(ExecutionMode mode) {
        return mode == ExecutionMode.WORKFLOW ? "直接按流程模板执行" : "自动匹配技能与助手";
    }

    private String skillDisplayName(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return skillId;
        }
        Optional<SkillCatalogIndexEntry> entry = skillCatalogService.findIndex(skillId.strip());
        return entry.map(SkillCatalogIndexEntry::displayName)
                .filter(StringUtils::hasText)
                .orElse(skillId.strip());
    }

    private String agentDisplayName(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return agentId;
        }
        Optional<AgentCatalogIndexEntry> entry = agentCatalogService.findIndex(agentId.strip());
        return entry.map(AgentCatalogIndexEntry::displayName)
                .filter(StringUtils::hasText)
                .orElse(agentId.strip());
    }

    private String workflowDisplayName(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return workflowId;
        }
        WorkflowManagerClient.WorkflowCatalogEntryDto entry = workflowCatalog.findEntry(workflowId.strip());
        if (entry == null) {
            return workflowId.strip();
        }
        if (StringUtils.hasText(entry.displayName())) {
            return entry.displayName().strip();
        }
        if (StringUtils.hasText(entry.description())) {
            return entry.description().strip();
        }
        return workflowId.strip();
    }

    private static String bracket(String name) {
        return "「" + (name != null ? name : "") + "」";
    }

    private void warnAndIgnoreHashWorkflow(RoutingContext ctx) {
        WorkflowBindingOutcome peek = workflowBindingParser.resolve(ctx.userMessage(), ctx.forcedWorkflowId());
        if (peek.bound() || peek.unknown()
                || (StringUtils.hasText(ctx.userMessage()) && ctx.userMessage().strip().startsWith("#"))) {
            log.warn("[ForcedExecutionRouter] Track A ignores #workflow kind={} messageId={}",
                    ctx.kindOrDefault(), ctx.traceMessageId());
        }
    }

    private void applyTrackRule(String userMessage, BindingAcc acc) {
        Optional<ExecutionPlan> hit = UnifiedRuleRoutingPolicy.matchForLockedMode(
                promptCatalogHolder.snapshot().routingRules(), userMessage, acc.locked);
        if (hit.isEmpty()) {
            return;
        }
        ExecutionPlan plan = hit.get();
        acc.sameModeRuleHit = true;
        if (acc.locked == ExecutionMode.WORKFLOW
                && !StringUtils.hasText(acc.workflowId)
                && StringUtils.hasText(plan.workflowId())) {
            acc.workflowId = plan.workflowId().strip();
        }
        if (acc.locked != ExecutionMode.WORKFLOW) {
            acc.mergeParamsFillGaps(plan.params());
        } else {
            acc.mergeParamsFillGaps(stripTrackAParams(plan.params()));
        }
        if (StringUtils.hasText(plan.ruleId())) {
            acc.ruleId = plan.ruleId();
            acc.trace("rule", "智能匹配", "命中常用处理规则");
        }
    }

    private static Map<String, String> stripTrackAParams(Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            if (SkillBindingOutcome.PARAM_SKILL.equals(e.getKey())
                    || PARAM_AGENT_IDS.equals(e.getKey())
                    || SkillBindingOutcome.PARAM_PLANNER_MODE.equals(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 钉死路径绑定累积：高优先级已有键不覆盖；同时记录各层命中 trace */
    private final class BindingAcc {
        private final ExecutionMode locked;
        private final String reason;
        private String workflowId;
        private final Map<String, String> params = new LinkedHashMap<>();
        private final List<RoutingTrace> traces = new ArrayList<>();
        private String ruleId;
        private boolean sameModeRuleHit;

        private BindingAcc(ExecutionMode locked, String reason) {
            this.locked = locked;
            this.reason = reason;
        }

        private void trace(String layer, String label, String detail) {
            traces.add(RoutingTrace.of(layer, label, detail));
        }

        private void mergeParamsFillGaps(Map<String, String> incoming) {
            if (incoming == null || incoming.isEmpty()) {
                return;
            }
            for (Map.Entry<String, String> e : incoming.entrySet()) {
                if (!StringUtils.hasText(e.getKey()) || !StringUtils.hasText(e.getValue())) {
                    continue;
                }
                if (locked == ExecutionMode.WORKFLOW
                        && (SkillBindingOutcome.PARAM_SKILL.equals(e.getKey())
                        || PARAM_AGENT_IDS.equals(e.getKey())
                        || SkillBindingOutcome.PARAM_PLANNER_MODE.equals(e.getKey()))) {
                    continue;
                }
                params.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        private void mergeFromL3(ExecutionPlan llm) {
            if (llm == null) {
                return;
            }
            if (locked == ExecutionMode.WORKFLOW
                    && !StringUtils.hasText(workflowId)
                    && StringUtils.hasText(llm.workflowId())) {
                workflowId = llm.workflowId().strip();
            }
            mergeParamsFillGaps(llm.params());
        }

        /** L0 命中：任意 /skill 或任意 $agent 即视为触发，跳过规则层与 L3 */
        private boolean hasL0Trigger() {
            return StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_SKILL))
                    || StringUtils.hasText(params.get(PARAM_AGENT_IDS));
        }

        private boolean needsL3() {
            return switch (locked) {
                case WORKFLOW -> !StringUtils.hasText(workflowId);
                case FAST -> !StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_SKILL));
                case PRO -> !sameModeRuleHit && !StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_SKILL));
            };
        }

        private ExecutionPlan toPlan() {
            traces.add(RoutingTrace.of("final", "执行方案", finalBindingText(locked, workflowId, params)));
            return new ExecutionPlan(
                    locked,
                    locked == ExecutionMode.WORKFLOW ? workflowId : null,
                    params.isEmpty() ? Map.of() : Map.copyOf(params),
                    reason,
                    ruleId,
                    List.copyOf(traces));
        }
    }
}
