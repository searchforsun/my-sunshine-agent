package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * 拉取 prompt-manager 全量 Catalog（对齐 SkillCatalogClient 的 base-url + WebClient 模式）。
 */
@Slf4j
@Component
public class PromptCatalogClient {

    private final WebClient webClient;

    public PromptCatalogClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
    }

    /**
     * 轻量版本探测：GET /api/prompts/catalog/version，仅返回 catalogVersion。
     * 供定时刷新先比对版本，无变化时跳过全量拉取（避免每 5 秒传输 159 条 prompt 正文）。
     */
    public long fetchVersion() {
        R<Long> body = webClient.get()
                .uri("/api/prompts/catalog/version")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<Long>>() {})
                .block();
        if (body == null || body.getData() == null) {
            throw new IllegalStateException("Prompt catalog version empty response from resource-manager");
        }
        return body.getData();
    }

    /**
     * GET /api/prompts/catalog → Snapshot。失败抛异常（供启动 fail-fast / 调用方自行捕获）。
     */
    public PromptCatalogSnapshot fetchSnapshot() {
        R<CatalogPayload> body = webClient.get()
                .uri("/api/prompts/catalog")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<CatalogPayload>>() {})
                .block();
        if (body == null || body.getData() == null) {
            throw new IllegalStateException("Prompt catalog empty response from resource-manager");
        }
        CatalogPayload data = body.getData();
        List<PromptCatalogEntry> entries = data.entries() != null ? data.entries() : List.of();
        log.debug("[PromptCatalogClient] fetched version={} entries={}", data.catalogVersion(), entries.size());
        return PromptCatalogSnapshot.of(data.catalogVersion(), entries);
    }

    /** 对齐 prompt-manager PromptCatalogResponse */
    public record CatalogPayload(long catalogVersion, List<PromptCatalogEntry> entries) {}
}
