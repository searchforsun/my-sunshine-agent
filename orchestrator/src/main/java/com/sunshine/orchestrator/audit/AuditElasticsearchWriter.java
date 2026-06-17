package com.sunshine.orchestrator.audit;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditElasticsearchWriter {

    private final AuditProperties properties;
    private WebClient webClient;

    @PostConstruct
    void init() {
        AuditProperties.Elasticsearch es = properties.getElasticsearch();
        if (!es.isEnabled()) {
            return;
        }
        webClient = WebClient.builder().baseUrl(es.getUrl()).build();
        log.info("[Audit-ES] enabled url={} index={}", es.getUrl(), es.getIndex());
    }

    public void index(AuditEvent event) {
        AuditProperties.Elasticsearch es = properties.getElasticsearch();
        if (!es.isEnabled() || webClient == null || event == null) {
            return;
        }
        try {
            webClient.put()
                    .uri("/{index}/_doc/{id}", es.getIndex(), event.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toDocument(event))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(3));
            log.debug("[Audit-ES] indexed id={}", event.id());
        } catch (Exception e) {
            log.warn("[Audit-ES] index failed id={}: {}", event.id(), e.getMessage());
        }
    }

    private static Map<String, Object> toDocument(AuditEvent event) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", event.id());
        doc.put("conversationId", event.conversationId());
        doc.put("messageId", event.messageId());
        doc.put("userId", event.userId());
        doc.put("tenantId", event.tenantId());
        doc.put("eventType", event.eventType());
        doc.put("status", event.status());
        doc.put("intent", event.intent());
        doc.put("contentLen", event.contentLen());
        doc.put("payload", event.payloadJson());
        doc.put("createdAt", event.createdAt() != null ? event.createdAt().toString() : null);
        return doc;
    }
}
