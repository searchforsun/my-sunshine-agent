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

    private final WebClient webClient;

    public SdkCatalogClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public SdkToolCatalogResponse fetchCatalog(String host, int port, String catalogPath, Duration timeout) {
        String path = catalogPath != null && !catalogPath.isBlank() ? catalogPath : "/sunshine/tools/catalog";
        String url = "http://" + host + ":" + port + path;
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
