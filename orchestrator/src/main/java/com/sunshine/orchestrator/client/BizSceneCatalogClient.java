package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/** 业务场景 Lab active 码闭集缓存（K2）：biz_scene 解析的合法码校验；拉取失败回退空集 */
@Slf4j
@Component
public class BizSceneCatalogClient {

    private final WebClient webClient;
    private volatile Set<String> activeCodes = Set.of();

    public BizSceneCatalogClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
    }

    public Set<String> activeCodes() {
        if (activeCodes.isEmpty()) {
            refresh();
        }
        return activeCodes;
    }

    public synchronized void refresh() {
        try {
            List<String> codes = webClient.get()
                    .uri("/api/biz-scenes/active-codes")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<String>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[BizSceneCatalogClient] fetch active codes failed: {}", e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            this.activeCodes = codes != null ? Set.copyOf(codes) : Set.of();
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] fetch active codes error: {}", e.getMessage());
        }
    }
}
