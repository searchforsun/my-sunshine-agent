package com.sunshine.rag.client;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.config.RagDesensitizeProperties;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 发布入库前正文脱敏 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesensitizeClient {

    private final RagDesensitizeProperties properties;
    private final WebClient webClient;

    public DesensitizeClient(RagDesensitizeProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
        log.info("[RAG] DesensitizeClient enabled={} baseUrl=http://sunshine-resource-manager", properties.isEnabled());
    }

    public String scrubForPublish(String text) {
        if (!properties.isEnabled() || !StringUtils.hasText(text)) {
            return text;
        }
        try {
            String scrubbed = webClient.post()
                    .uri("/api/desensitize/scrub")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<Map<String, String>>>() {})
                    .map(r -> r.getData() != null && r.getData().get("text") != null
                            ? r.getData().get("text")
                            : text)
                    .block();
            return scrubbed != null ? scrubbed : text;
        } catch (Exception e) {
            log.warn("[RAG] 脱敏失败: {}", e.getMessage());
            if (properties.isFailOnError()) {
                throw new BizException(RagErrorCode.INGEST_DESENSITIZE_FAILED);
            }
            return text;
        }
    }
}
