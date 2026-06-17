package com.sunshine.orchestrator.memory.mtm;

import com.sunshine.orchestrator.client.MemoryRagClient;
import com.sunshine.orchestrator.memory.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MTM — 跨会话情景记忆：MySQL 元数据 + Milvus 语义召回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MtmService {

    private final ConversationMemoryRepository memoryRepo;
    private final MemoryRagClient memoryRagClient;
    private final MemoryProperties memoryProperties;

    public Optional<String> recallSnippet(String userId, String tenantId, String query, String excludeConvId) {
        if (!memoryProperties.isEnabled() || !memoryProperties.getMtm().isEnabled()) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        MemoryProperties.Mtm cfg = memoryProperties.getMtm();

        List<MemoryRagClient.MemoryHit> hits = memoryRagClient
                .search(userId, tid, query, cfg.getTopK())
                .blockOptional()
                .orElse(List.of());

        List<String> lines = hits.stream()
                .filter(h -> excludeConvId == null || !excludeConvId.equals(h.convId()))
                .filter(h -> h.score() >= cfg.getMinScore())
                .map(h -> "- " + h.summary().strip())
                .limit(cfg.getTopK())
                .collect(Collectors.toList());

        if (lines.isEmpty()) {
            lines = fallbackRecent(userId, tid, excludeConvId);
        }

        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("[相关历史情景 · MTM]\n" + String.join("\n", lines));
    }

    @Transactional
    public void saveSummary(
            String userId, String tenantId, String convId, String summary, String intent) {
        if (!StringUtils.hasText(summary)) {
            return;
        }
        String tid = tenantId != null ? tenantId : "default";
        Instant now = Instant.now();

        ConversationMemoryEntity entity = memoryRepo.findByConvId(convId).orElseGet(() -> {
            ConversationMemoryEntity e = new ConversationMemoryEntity();
            e.setId(newId());
            e.setUserId(userId);
            e.setTenantId(tid);
            e.setConvId(convId);
            e.setHeatScore(0);
            e.setCreatedAt(now);
            return e;
        });
        entity.setSummary(summary.strip());
        entity.setIntent(intent);
        entity.setUpdatedAt(now);
        memoryRepo.save(entity);

        memoryRagClient.upsert(userId, tid, convId, summary.strip()).subscribe();
        log.info("[MTM] 会话摘要已保存 conv={} len={}", convId, summary.length());
    }

    private List<String> fallbackRecent(String userId, String tenantId, String excludeConvId) {
        return memoryRepo.findTop5ByUserIdAndTenantIdOrderByUpdatedAtDesc(userId, tenantId).stream()
                .filter(e -> excludeConvId == null || !excludeConvId.equals(e.getConvId()))
                .limit(memoryProperties.getMtm().getTopK())
                .map(e -> "- " + e.getSummary().strip())
                .toList();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
