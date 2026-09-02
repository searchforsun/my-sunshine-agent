package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可取消沙箱工具 in-flight 句柄 + 用户取消后同族再调用预算。
 * 禁止 bump stream epoch。
 */
@Slf4j
@Component
public class CancellableToolRunRegistry {

    private final SandboxClient sandboxClient;
    private final AgentSandboxProperties sandboxProperties;
    private final ConcurrentHashMap<String, Handle> byToolUseId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> toolUseIdByStepId = new ConcurrentHashMap<>();
    /** messageId → 已取消命令签名集合（同命令重试禁绝；换命令/换工具放行） */
    private final ConcurrentHashMap<String, Set<String>> cancelledByMessage = new ConcurrentHashMap<>();
    private final Set<String> recentlyCancelled = ConcurrentHashMap.newKeySet();
    /** PreActing 已出卡、execute 尚未 register：stepId → messageId */
    private final ConcurrentHashMap<String, String> pendingCancelStepIds = new ConcurrentHashMap<>();
    /** toolUseId → messageId（裸 id 提前取消） */
    private final ConcurrentHashMap<String, String> pendingCancelToolUseIds = new ConcurrentHashMap<>();

    public CancellableToolRunRegistry(SandboxClient sandboxClient, AgentSandboxProperties sandboxProperties) {
        this.sandboxClient = sandboxClient;
        this.sandboxProperties = sandboxProperties;
    }

    public boolean isCancellableTool(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return false;
        }
        List<String> list = sandboxProperties.getCancellableTools();
        if (list == null || list.isEmpty()) {
            return false;
        }
        return list.stream().anyMatch(toolName::equals);
    }

    public void register(
            String toolUseId, String messageId, String toolName, String sessionId, String invocationId) {
        register(toolUseId, messageId, toolName, sessionId, invocationId, null);
    }

    /**
     * @param expandDetail 取消时 Controller 写入展开区的快照（命令/pattern）；可后续 bindExpandDetail
     */
    public void register(
            String toolUseId,
            String messageId,
            String toolName,
            String sessionId,
            String invocationId,
            String expandDetail) {
        if (!StringUtils.hasText(toolUseId)) {
            return;
        }
        if (!StringUtils.hasText(messageId)) {
            log.warn("[CancellableTool] register skip: blank messageId toolUseId={}", toolUseId);
            return;
        }
        String id = toolUseId.strip();
        String mid = messageId.strip();
        String stepId = StepEventBridge.stepIdForToolUse(id);
        Handle existing = byToolUseId.get(id);
        if (existing != null) {
            if (StringUtils.hasText(sessionId)) {
                existing.sessionId = sessionId.strip();
            }
            if (StringUtils.hasText(expandDetail)) {
                existing.expandDetail = expandDetail.strip();
            }
            if (StringUtils.hasText(stepId)) {
                toolUseIdByStepId.put(stepId.strip(), id);
            }
            if (existing.cancelled.get() || consumePendingCancel(id, stepId, existing.messageId)) {
                cancel(id);
            }
            return;
        }
        Handle handle = new Handle(
                id,
                mid,
                toolName,
                sessionId,
                invocationId != null ? invocationId.strip() : id,
                stepId,
                expandDetail);
        byToolUseId.put(id, handle);
        if (StringUtils.hasText(stepId)) {
            toolUseIdByStepId.put(stepId.strip(), id);
        }
        recentlyCancelled.remove(id);
        if (consumePendingCancel(id, stepId, mid)) {
            cancel(id);
        }
    }

    /** execute 入口补全取消展开快照（PreActing 未带参时） */
    public void bindExpandDetail(String toolUseId, String expandDetail) {
        if (!StringUtils.hasText(toolUseId) || !StringUtils.hasText(expandDetail)) {
            return;
        }
        Handle handle = byToolUseId.get(toolUseId.strip());
        if (handle != null) {
            handle.expandDetail = expandDetail.strip();
        }
    }

    /** pending 仅当 messageId 匹配（或任一侧未绑 message）时生效；错属则写回 pending */
    private boolean consumePendingCancel(String toolUseId, String stepId, String messageId) {
        String pendingByTool = pendingCancelToolUseIds.remove(toolUseId);
        String sid = StringUtils.hasText(stepId) ? stepId.strip() : null;
        String pendingByStep = sid != null ? pendingCancelStepIds.remove(sid) : null;
        if (pendingByTool == null && pendingByStep == null) {
            return false;
        }
        String pendingMsg = pendingByTool != null ? pendingByTool : pendingByStep;
        if (!StringUtils.hasText(pendingMsg) || !StringUtils.hasText(messageId)
                || pendingMsg.equals(messageId.strip())) {
            return true;
        }
        if (pendingByTool != null) {
            pendingCancelToolUseIds.put(toolUseId, pendingByTool);
        }
        if (pendingByStep != null && sid != null) {
            pendingCancelStepIds.put(sid, pendingByStep);
        }
        return false;
    }

    /** ensureBound 之后补全 sessionId，便于 kill */
    public void bindSession(String toolUseId, String sessionId) {
        if (!StringUtils.hasText(toolUseId) || !StringUtils.hasText(sessionId)) {
            return;
        }
        Handle handle = byToolUseId.get(toolUseId.strip());
        if (handle != null) {
            handle.sessionId = sessionId.strip();
        }
    }

    public boolean isCancelled(String toolUseId) {
        if (!StringUtils.hasText(toolUseId)) {
            return false;
        }
        Handle h = byToolUseId.get(toolUseId.strip());
        return h != null && h.cancelled.get();
    }

    /** PostActing 消费：是否刚被用户取消（unregister 后仍可查一次） */
    public boolean consumeRecentlyCancelled(String toolUseId) {
        if (!StringUtils.hasText(toolUseId)) {
            return false;
        }
        return recentlyCancelled.remove(toolUseId.strip());
    }

    /**
     * 同命令重试禁绝：messageId 下已取消的命令签名命中则拒调；换命令/换参数/换工具放行。
     * @return false 表示该命令此前已被取消应拒调
     */
    public boolean tryConsumeFollowup(String messageId, String toolName, Map<String, Object> body) {
        if (!StringUtils.hasText(messageId) || !isCancellableTool(toolName)) {
            return true;
        }
        Set<String> blocked = cancelledByMessage.get(messageId.strip());
        if (blocked == null || blocked.isEmpty()) {
            return true;
        }
        String detail = SandboxCancelExpand.detail(toolName, body);
        if (!StringUtils.hasText(detail)) {
            return true;
        }
        return !blocked.contains(signatureOf(toolName, detail));
    }

    /** 记录已取消的命令签名（同命令原样重试禁绝） */
    public void recordCancelled(String messageId, String toolName, String signature) {
        if (!StringUtils.hasText(messageId) || !StringUtils.hasText(toolName)
                || !StringUtils.hasText(signature)) {
            return;
        }
        cancelledByMessage
                .computeIfAbsent(messageId.strip(), k -> ConcurrentHashMap.newKeySet())
                .add(signatureOf(toolName, signature));
    }

    public Handle get(String toolUseId) {
        if (!StringUtils.hasText(toolUseId)) {
            return null;
        }
        return byToolUseId.get(toolUseId.strip());
    }

    /** 按时间线 step.id（tool-*）反查句柄 */
    public Handle getByStepId(String stepId) {
        if (!StringUtils.hasText(stepId)) {
            return null;
        }
        String sid = stepId.strip();
        String mapped = toolUseIdByStepId.get(sid);
        if (StringUtils.hasText(mapped)) {
            return byToolUseId.get(mapped);
        }
        for (Handle handle : byToolUseId.values()) {
            String bound = handle.stepId();
            if (!StringUtils.hasText(bound)) {
                bound = StepEventBridge.stepIdForToolUse(handle.toolUseId());
            }
            if (sid.equals(bound)) {
                return handle;
            }
        }
        return null;
    }

    /**
     * 工具卡已 running、execute 尚未 register：记 pending（绑定 messageId），register 时立即 cancel。
     */
    public boolean markPendingCancelByStepId(String stepId, String messageId) {
        if (!StringUtils.hasText(stepId) || !StringUtils.hasText(messageId)) {
            return false;
        }
        String sid = stepId.strip();
        pendingCancelStepIds.put(sid, messageId.strip());
        log.info("[CancellableTool] cancelByStepId pending stepId={} messageId={} inflight={}",
                sid, messageId, byToolUseId.size());
        return true;
    }

    /** 裸 toolUseId 提前取消（绑定 messageId） */
    public boolean markPendingCancel(String toolUseId, String messageId) {
        if (!StringUtils.hasText(toolUseId) || !StringUtils.hasText(messageId)) {
            return false;
        }
        String id = toolUseId.strip();
        pendingCancelToolUseIds.put(id, messageId.strip());
        log.info("[CancellableTool] cancel pending toolUseId={} messageId={}", id, messageId);
        return true;
    }

    /**
     * 取消指定工具调用。成功返回 true；未知 id 返回 false（须先 markPending）。
     * 不调用 GenerationRegistry.cancel / bumpStreamEpoch。
     */
    public boolean cancel(String toolUseId) {
        if (!StringUtils.hasText(toolUseId)) {
            return false;
        }
        String id = toolUseId.strip();
        Handle handle = byToolUseId.get(id);
        if (handle == null) {
            return false;
        }
        if (!handle.cancelled.compareAndSet(false, true)) {
            return true;
        }
        recentlyCancelled.add(id);
        if (StringUtils.hasText(handle.sessionId) && StringUtils.hasText(handle.invocationId)) {
            try {
                sandboxClient.cancelInvocation(handle.sessionId, handle.invocationId);
            } catch (Exception e) {
                log.warn("[CancellableTool] sandbox cancel failed toolUseId={}: {}", id, e.getMessage());
            }
        }
        recordCancelled(handle.messageId, handle.toolName, handle.expandDetail);
        log.info("[CancellableTool] cancel toolUseId={} tool={} messageId={}",
                id, handle.toolName, handle.messageId);
        return true;
    }

    public void unregister(String toolUseId) {
        if (!StringUtils.hasText(toolUseId)) {
            return;
        }
        String id = toolUseId.strip();
        Handle removed = byToolUseId.remove(id);
        if (removed != null && StringUtils.hasText(removed.stepId())) {
            toolUseIdByStepId.remove(removed.stepId(), id);
        }
    }

    private static String signatureOf(String toolName, String signature) {
        return toolName + ":" + signature.strip();
    }

    public static final class Handle {
        private final String toolUseId;
        private final String messageId;
        private final String toolName;
        private volatile String sessionId;
        private final String invocationId;
        private final String stepId;
        private volatile String expandDetail;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        Handle(
                String toolUseId,
                String messageId,
                String toolName,
                String sessionId,
                String invocationId,
                String stepId,
                String expandDetail) {
            this.toolUseId = toolUseId;
            this.messageId = messageId;
            this.toolName = toolName;
            this.sessionId = sessionId;
            this.invocationId = invocationId;
            this.stepId = stepId;
            this.expandDetail = StringUtils.hasText(expandDetail) ? expandDetail.strip() : null;
        }

        public String toolUseId() {
            return toolUseId;
        }

        public String messageId() {
            return messageId;
        }

        public String toolName() {
            return toolName;
        }

        public String sessionId() {
            return sessionId;
        }

        public String invocationId() {
            return invocationId;
        }

        public String stepId() {
            return stepId;
        }

        public String expandDetail() {
            return expandDetail;
        }

        public boolean cancelled() {
            return cancelled.get();
        }
    }
}
