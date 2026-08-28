package com.sunshine.orchestrator.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 4.7.7 ReAct 目标对齐 + 失败预算 — per-run 状态载体。
 * 挂 {@link StepEventBridge}（bridgeId 生命周期），clear 时随 bridge 一并回收（天然防泄漏）；
 * 续跑 resume 重建 bridge 即重置（可接受：续跑是新 run）。
 * Middleware 无状态（P2-1 E5），per-run 状态全部集中于此，不落 AgentState.context。
 */
public final class AgentRunState {

    /** 目标对齐：reasoning 轮次计数（每次 onReasoning 递增） */
    private int reasoningIter;
    /** 目标对齐：上次注入提醒的轮次（0=从未注入） */
    private int goalCheckLastInjectedIter;
    /** 目标对齐：上次注入后是否发生过业务工具完成（连续纯 think 不重复轰炸） */
    private boolean toolDoneSinceLastInject;
    /** 失败预算：key（toolName 或 toolName#指纹）→ 连续失败次数；成功即清零该 key */
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
    /** 失败预算：已触发过强提示的 key（每 run 每 key 只触发一次） */
    private final Set<String> budgetTriggeredKeys = ConcurrentHashMap.newKeySet();
    /** 达阈值待注入的强提示（onActing 标记 → 下一轮 onReasoning 注入后清空） */
    private final Map<String, PendingBudget> pendingBudgetInjections = new ConcurrentHashMap<>();
    /** 达阈值那次的 toolUseId（ProcessingStepMiddleware 换 tool 步 after 文案用） */
    private final Set<String> budgetExceededToolUseIds = ConcurrentHashMap.newKeySet();

    /** 失败预算强提示载荷：模板占位符 {toolName} / {failCount} / {lastError} */
    public record PendingBudget(String toolName, int failCount, String lastError) {
    }

    public synchronized int nextReasoningIter() {
        return ++reasoningIter;
    }

    public synchronized int goalCheckLastInjectedIter() {
        return goalCheckLastInjectedIter;
    }

    public synchronized boolean toolDoneSinceLastInject() {
        return toolDoneSinceLastInject;
    }

    /** 业务工具完成标记（goal-check 工具闸门）：距上次注入后置位一次 */
    public synchronized void markToolDone() {
        toolDoneSinceLastInject = true;
    }

    /** 注入 goal-check 后记录轮次并复位工具闸门 */
    public synchronized void markGoalCheckInjected(int iter) {
        goalCheckLastInjectedIter = iter;
        toolDoneSinceLastInject = false;
    }

    public int failureCount(String key) {
        return key != null ? failureCounts.getOrDefault(key, 0) : 0;
    }

    /** 递增失败计数；达阈值且未触发过则登记待注入强提示（每 run 每 key 一次） */
    public void recordFailure(String key, int threshold, String toolName, String lastError, String toolUseId) {
        if (key == null || threshold <= 0) {
            return;
        }
        int count = failureCounts.merge(key, 1, Integer::sum);
        if (count >= threshold && budgetTriggeredKeys.add(key)) {
            // 同一 toolUseId 双维度同时达阈值时只注入一条（防重复轰炸）
            if (toolUseId == null || budgetExceededToolUseIds.add(toolUseId)) {
                pendingBudgetInjections.put(key, new PendingBudget(toolName, count, lastError));
            }
        }
    }

    /** 成功清零指定 key（同参数成功 → signature 与 toolName 双维度都清零） */
    public void resetFailure(String key) {
        if (key != null) {
            failureCounts.remove(key);
        }
    }

    public boolean hasPendingBudgetInjection() {
        return !pendingBudgetInjections.isEmpty();
    }

    /** 取出全部待注入强提示并清空（一次性；注入后下一轮不再重复） */
    public List<PendingBudget> drainPendingBudgetInjections() {
        if (pendingBudgetInjections.isEmpty()) {
            return List.of();
        }
        Map<String, PendingBudget> out = new LinkedHashMap<>(pendingBudgetInjections);
        pendingBudgetInjections.clear();
        return new ArrayList<>(out.values());
    }

    /** budget 触发的 tool 步（达阈值那次的 toolUseId）— completeToolStep 换 after 文案用 */
    public boolean isBudgetExceeded(String toolUseId) {
        return toolUseId != null && budgetExceededToolUseIds.contains(toolUseId);
    }
}
