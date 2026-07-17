package com.sunshine.orchestrator.sandbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Set;

/** 关闭 Redis TTL 到期的对话级沙箱容器 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxSessionReaper {

    private final ConversationSandboxStore store;
    private final com.sunshine.orchestrator.client.SandboxClient sandboxClient;

    @Scheduled(fixedDelayString = "${agent.sandbox.reaper-interval-ms:60000}")
    public void reapExpired() {
        long now = Instant.now().toEpochMilli();
        Set<String> members = store.pollExpiredMembers(now);
        if (members.isEmpty()) {
            return;
        }
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
                store.remove(tenantId, conversationId);
                if (StringUtils.hasText(sessionId)) {
                    sandboxClient.closeSession(sessionId);
                    log.info("[SandboxReaper] closed expired session={} conv={}", sessionId, conversationId);
                }
            } catch (Exception e) {
                log.warn("[SandboxReaper] failed member={}: {}", member, e.getMessage());
            } finally {
                store.removeExpiryMember(member);
            }
        }
    }
}
