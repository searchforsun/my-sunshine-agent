package com.sunshine.bff.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AuthCenterClient {

    @Value("${auth-center.base-url:http://localhost:8100}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("[BFF] AuthCenter 客户端: baseUrl={}", baseUrl);
    }

    /** userId → 展示名（nickname 优先，否则 username） */
    public Mono<Map<String, String>> lookupDisplayNames(Collection<String> userIds) {
        List<String> ids = userIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        String joined = String.join(",", ids);
        return webClient.get()
                .uri(uri -> uri.path("/api/internal/users/brief").queryParam("ids", joined).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(AuthCenterClient::parseDisplayNames)
                .onErrorResume(e -> {
                    log.warn("[BFF] auth brief lookup failed: {}", e.getMessage());
                    return Mono.just(Map.of());
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseDisplayNames(Map<String, Object> body) {
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            String userId = stringValue(row.get("userId"));
            if (!StringUtils.hasText(userId)) {
                continue;
            }
            String nickname = stringValue(row.get("nickname"));
            String username = stringValue(row.get("username"));
            names.put(userId, StringUtils.hasText(nickname) ? nickname : username);
        }
        return names;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
