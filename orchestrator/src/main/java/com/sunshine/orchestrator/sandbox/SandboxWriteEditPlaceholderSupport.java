package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * write/edit 参数流占位：ToolCallStart 即开工具步并下发 active「正在写入…」，
 * Delta 解析到 path 后刷新主行；避免大 content 生成空档只有三点、看不到正在写哪个文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxWriteEditPlaceholderSupport {

    private final ToolCatalogService toolCatalogService;
    private final SandboxTimelineLabelService sandboxTimelineLabels;

    /** toolCallId → 已累积的 arguments 原文 */
    private final ConcurrentHashMap<String, StringBuilder> argsByToolCall = new ConcurrentHashMap<>();
    /** toolCallId → 已展示的 path（避免重复 progress） */
    private final ConcurrentHashMap<String, String> pathByToolCall = new ConcurrentHashMap<>();
    /** toolCallId → 真实工具名（后续 Delta 片可能是 FRAGMENT 占位名） */
    private final ConcurrentHashMap<String, String> toolNameByCall = new ConcurrentHashMap<>();

    public boolean isWriteOrEdit(String toolName) {
        return SandboxIds.WRITE.equals(toolName) || SandboxIds.EDIT.equals(toolName);
    }

    public void onToolCallStart(String bridgeId, String toolCallId, String toolName) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(toolCallId) || !isWriteOrEdit(toolName)) {
            return;
        }
        toolNameByCall.put(toolCallId, toolName);
        if (StepEventBridge.stepIdForToolUse(toolCallId) != null) {
            return;
        }
        argsByToolCall.put(toolCallId, new StringBuilder());
        String baseStepId = toolCatalogService.timelineStepId(toolName);
        String phase = toolCatalogService.timelinePhase(toolName);
        String active = sandboxTimelineLabels.active(toolName, toolCatalogService.displayName(toolName), Map.of());
        final String[] stepHolder = new String[1];
        StepEventBridge.bindToolUseBridge(toolCallId, bridgeId);
        StepEventBridge.emit(bridgeId, session -> {
            session.noteToolCallPending();
            stepHolder[0] = session.beginToolStep(baseStepId, phase);
            if (stepHolder[0] != null && StringUtils.hasText(active)) {
                session.progress(stepHolder[0], active);
            }
        });
        if (stepHolder[0] != null) {
            StepEventBridge.bindToolUseStep(toolCallId, stepHolder[0]);
            log.debug("[WriteEditPlaceholder] open stepId={} tool={} bridge={}",
                    stepHolder[0], toolName, bridgeId);
        }
    }

    public void onToolCallDelta(String bridgeId, String toolCallId, String toolName, String delta) {
        if (!StringUtils.hasText(bridgeId) || !StringUtils.hasText(toolCallId)) {
            return;
        }
        // AgentScope 后续 arguments 片 name 可能是 FRAGMENT 占位，回查 Start 时登记的真名
        String resolvedName = isWriteOrEdit(toolName) ? toolName : toolNameByCall.get(toolCallId);
        if (!isWriteOrEdit(resolvedName)) {
            return;
        }
        if (!StringUtils.hasText(delta)) {
            return;
        }
        // Start 可能丢事件：Delta 首包也尝试开步
        if (StepEventBridge.stepIdForToolUse(toolCallId) == null) {
            onToolCallStart(bridgeId, toolCallId, resolvedName);
        }
        String stepId = StepEventBridge.stepIdForToolUse(toolCallId);
        if (stepId == null) {
            return;
        }
        StringBuilder acc = argsByToolCall.computeIfAbsent(toolCallId, k -> new StringBuilder());
        acc.append(delta);
        String path = SandboxToolArgPathParser.extractPath(acc.toString());
        if (!StringUtils.hasText(path)) {
            return;
        }
        String prev = pathByToolCall.put(toolCallId, path);
        if (path.equals(prev)) {
            return;
        }
        String active = sandboxTimelineLabels.active(
                resolvedName, toolCatalogService.displayName(resolvedName), Map.of("path", path));
        if (!StringUtils.hasText(active)) {
            return;
        }
        StepEventBridge.emit(bridgeId, session -> session.progress(stepId, active));
        log.debug("[WriteEditPlaceholder] path stepId={} path={}", stepId, path);
    }

    public void onToolCallEnd(String toolCallId) {
        if (!StringUtils.hasText(toolCallId)) {
            return;
        }
        // 保留 step/toolName 绑定给 onActing 复用；仅清累积缓冲
        argsByToolCall.remove(toolCallId);
        pathByToolCall.remove(toolCallId);
    }

    /** onActing 复用占位步后清理残留缓冲 */
    public void clearBuffers(String toolCallId) {
        if (!StringUtils.hasText(toolCallId)) {
            return;
        }
        argsByToolCall.remove(toolCallId);
        pathByToolCall.remove(toolCallId);
        toolNameByCall.remove(toolCallId);
    }
}
