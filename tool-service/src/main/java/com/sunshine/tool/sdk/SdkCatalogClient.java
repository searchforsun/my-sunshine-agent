package com.sunshine.tool.sdk;

import com.sunshine.tools.sdk.dto.SdkToolCatalogResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Component
public class SdkCatalogClient {

    /** 注入 sunshine-common 的 @LoadBalanced WebClient.Builder，按 Nacos 服务名解析实例。 */
    private final WebClient webClient;

    public SdkCatalogClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public SdkToolCatalogResponse fetchCatalog(String serviceName, String catalogPath, Duration timeout) {
        String path = catalogPath != null && !catalogPath.isBlank() ? catalogPath : "/sunshine/tools/catalog";
        String url = "http://" + serviceName + path;
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(SdkToolCatalogResponse.class)
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.warn("[SdkCatalogClient] fetch failed url={}: {}", url, e.getMessage());
                    return Mono.empty();
                })
                .block();
    }
}
