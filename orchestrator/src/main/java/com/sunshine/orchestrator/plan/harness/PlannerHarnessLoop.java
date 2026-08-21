package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;

/**
 * Planner-Executor 顶层编排（v17 简化）：仅做熔断 + 启动 Planner 一次性 ReAct run。
 * <p>
 * Planner 是「带 plan_submit / dispatch_worker / self_assess 元工具的普通 ReAct」，与 Subagent 同构：
 * think → plan_submit → dispatch_worker（同步等 handoff）→ ... → 最终 content 即综合回答。
 * Worker 内部 ReAct 折叠为 worker-{taskId} 卡，与 Subagent 行为一致。
 * <p>
 * 本类职责收敛：
 * <ul>
 *   <li>Planner run 启动 / 异常收束（落盘已有结果）</li>
 *   <li>墙钟熔断（maxDurationMs）</li>
 *   <li>Planner LLM 自身 maxIters 兜底（由 HarnessPlanner 注入）</li>
 * </ul>
 * 任务执行 / RoundRecord / self_assess 信号判定全部下沉到 Planner 工具自身（dispatch_worker、
 * self_assess）；Engine 不再机械驱动循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerHarnessLoop {

    private final HarnessPlanner planner;
    private final PlanNotebookStore store;
    private final AgentExecutionProperties executionProperties;

    public Flux<StreamToken> run(ExecutionStreamContext ctx, PlanNotebook notebook) {
        return Flux.defer(() -> {
            Instant startedAt = Instant.now();
            try {
                Flux<StreamToken> plannerRun = planner.runPlanned(notebook, ctx)
                        .doOnCancel(() -> log.info("[PlannerHarnessLoop] Planner run 被取消 session={}",
                                notebook.getSessionId()));
                Flux<StreamToken> gated = applyWallClockGate(plannerRun, startedAt);
                // 单一收束：成功 / 异常 / 取消都落到这里落盘一次，避免重复 save
                return gated.doFinally(sig -> store.save(notebook));
            } catch (RuntimeException e) {
                log.warn("[PlannerHarnessLoop] 启动 Planner 失败: {}", e.getMessage());
                store.save(notebook);
                return Flux.error(e);
            }
        });
    }

    /**
     * 墙钟熔断：maxDurationMs > 0 时，超时后切断 token 流（不抛错）。
     * 防止 Planner run 在异常路径（如 LLM 卡死）下无限持续，污染会话。
     */
    private Flux<StreamToken> applyWallClockGate(Flux<StreamToken> source, Instant startedAt) {
        long maxMs = executionProperties.getHarness().getMaxDurationMs();
        if (maxMs <= 0) {
            return source;
        }
        long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
        long remaining = Math.max(0, maxMs - elapsed);
        return source.timeout(Duration.ofMillis(remaining))
                .onErrorResume(err -> {
                    if (err instanceof java.util.concurrent.TimeoutException
                            || (err.getMessage() != null && err.getMessage().contains("Did not observe"))) {
                        log.warn("[PlannerHarnessLoop] 墙钟熔断 maxDurationMs={} session={}",
                                maxMs, startedAt);
                        return Flux.empty();
                    }
                    return Flux.error(err);
                });
    }
}
