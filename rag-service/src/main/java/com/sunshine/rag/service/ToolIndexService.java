package com.sunshine.rag.service;

import com.sunshine.rag.config.ToolIndexProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Comparator;
import java.util.List;

/**
 * 工具语义索引服务：目录向量化入 Milvus + query 检索 Top-K。
 * Embedding 复用 {@link EmbeddingService}（text-embedding-v4），工具目录规模小，全量重建幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolIndexService {

    private final ToolMilvusService toolMilvusService;
    private final EmbeddingService embeddingService;
    private final ToolIndexProperties properties;

    /** 全量重建租户工具索引：逐条 embed（顺序执行，控制上游 QPS），失败单条降级跳过。 */
    public Mono<Void> sync(String tenantId, List<ToolIndexDoc> tools) {
        if (!properties.isEnabled()) {
            log.warn("[ToolIndex] 索引开关关闭，跳过 sync");
            return Mono.empty();
        }
        if (tools == null || tools.isEmpty()) {
            return Mono.fromRunnable(() -> toolMilvusService.replaceAll(tenantId, List.of()));
        }
        List<ToolIndexDoc> valid = tools.stream()
                .filter(doc -> doc.toolId() != null && !doc.toolId().isBlank())
                .toList();
        return Flux.fromIterable(valid)
                .concatMap(doc -> embeddingService.embed(doc.embeddingText())
                        .map(vector -> new ToolMilvusService.ToolIndexRow(
                                doc.toolId().strip(),
                                doc.name() != null ? doc.name() : doc.toolId(),
                                doc.description() != null ? doc.description() : "",
                                vector))
                        .onErrorResume(e -> {
                            log.warn("[ToolIndex] 工具 embed 失败 tool={}: {}", doc.toolId(), e.getMessage());
                            return Mono.empty();
                        }))
                .collectList()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(rows -> toolMilvusService.replaceAll(tenantId, rows))
                .then();
    }

    /** query → Top-K 工具命中（按相似度降序，过滤低分）。minScore 请求覆盖 Nacos，null 走配置默认。 */
    public Mono<List<ToolIndexHit>> search(String query, Integer topK, String tenantId, Float minScore) {
        if (!properties.isEnabled()) {
            return Mono.just(List.of());
        }
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        int k = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        float min = minScore != null ? minScore : properties.getMinScore();
        return embeddingService.embed(query)
                .map(vector -> toolMilvusService.search(tenantId, vector, k))
                .map(hits -> hits.stream()
                        .filter(hit -> hit.score() >= min)
                        .sorted(Comparator.comparingDouble(ToolMilvusService.ToolIndexHit::score).reversed())
                        .map(hit -> new ToolIndexHit(hit.toolId(), hit.score()))
                        .toList())
                .onErrorResume(e -> {
                    log.warn("[ToolIndex] 检索失败: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /** 工具目录条目：embeddingText = 名称 + 描述 + 参数摘要拼接的检索文本。 */
    public record ToolIndexDoc(String toolId, String name, String description, String paramsSummary) {

        public String embeddingText() {
            StringBuilder sb = new StringBuilder();
            if (name != null && !name.isBlank()) {
                sb.append(name).append('：');
            }
            if (description != null && !description.isBlank()) {
                sb.append(description);
            }
            if (paramsSummary != null && !paramsSummary.isBlank()) {
                sb.append("，参数：").append(paramsSummary);
            }
            return sb.toString();
        }
    }

    public record ToolIndexHit(String toolId, float score) {
    }
}
