package com.sunshine.orchestrator.sandbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Set;

/**
 * 双层回收：idle → docker stop；purge → docker rm + 清盘。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxSessionReaper {

    private final ConversationSandboxStore store;
    private final WorkspaceSandboxStore workspaceStore;
    private final com.sunshine.orchestrator.client.SandboxClient sandboxClient;

    @Scheduled(fixedDelayString = "${agent.sandbox.reaper-interval-ms:60000}")
    public void reap() {
        long now = Instant.now().toEpochMilli();
        reapIdleStop(now);
        reapPurgeDestroy(now);
        reapWorkspaceIdleStop(now);
    }

    void reapIdleStop(long nowEpochMs) {
        Set<String> members = store.pollExpiredMembers(nowEpochMs);
        for (String member : members) {
            String[] parts = ConversationSandboxStore.splitMember(member);
            if (parts.length < 3) {
                store.removeExpiryMember(member);
                continue;
            }
            String sessionId = parts[0];
            String tenantId = parts[1];
            String conversationId = parts[2];
            try {
                if (StringUtils.hasText(sessionId)) {
                    sandboxClient.stopSession(sessionId);
                }
                store.markStopped(tenantId, conversationId);
                log.info("[SandboxReaper] stopped idle session={} conv={}", sessionId, conversationId);
            } catch (Exception e) {
                log.warn("[SandboxReaper] stop failed member={}: {}", member, e.getMessage());
            } finally {
                store.removeExpiryMember(member);
            }
        }
    }

    void reapPurgeDestroy(long nowEpochMs) {
        Set<String> members = store.pollPurgeMembers(nowEpochMs);
        for (String member : members) {
            String[] parts = ConversationSandboxStore.splitMember(member);
            if (parts.length < 3) {
                store.removePurgeMember(member);
                continue;
            }
            String sessionId = parts[0];
            String tenantId = parts[1];
            String conversationId = parts[2];
            try {
                store.remove(tenantId, conversationId);
                if (StringUtils.hasText(sessionId)) {
                    sandboxClient.closeSession(sessionId);
                }
                log.info("[SandboxReaper] purged session={} conv={}", sessionId, conversationId);
            } catch (Exception e) {
                log.warn("[SandboxReaper] purge failed member={}: {}", member, e.getMessage());
            } finally {
                store.removePurgeMember(member);
            }
        }
    }

    void reapWorkspaceIdleStop(long nowEpochMs) {
        Set<String> members = workspaceStore.pollIdleMembers(nowEpochMs);
        for (String member : members) {
            String[] parts = WorkspaceSandboxStore.splitMember(member);
            if (parts.length < 3) {
                workspaceStore.removeIdleMember(member);
                continue;
            }
            String sessionId = parts[0];
            String tenantId = parts[1];
            String workspaceId = parts[2];
            try {
                if (StringUtils.hasText(sessionId)) {
                    sandboxClient.stopSession(sessionId);
                }
                workspaceStore.markStopped(tenantId, workspaceId);
                log.info("[SandboxReaper] stopped idle workspace session={} ws={}", sessionId, workspaceId);
            } catch (Exception e) {
                log.warn("[SandboxReaper] workspace stop failed member={}: {}", member, e.getMessage());
            } finally {
                workspaceStore.removeIdleMember(member);
            }
        }
    }
}
