package com.sunshine.orchestrator.rag;

import com.sunshine.orchestrator.client.RagClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 租户默认知识库解析 — 未传 kbId 时查 rag-service is_default。
 */
@Component
@RequiredArgsConstructor
public class DefaultKbResolver {

    private final RagClient ragClient;

    public Mono<String> resolve(String tenantId, String explicitKbId) {
        if (explicitKbId != null && !explicitKbId.isBlank()) {
            return Mono.just(explicitKbId.strip());
        }
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        return ragClient.fetchDefaultKbId(tid).defaultIfEmpty("default");
    }

    public String resolveBlocking(String tenantId, String explicitKbId) {
        return resolve(tenantId, explicitKbId).blockOptional().orElse("default");
    }
}
