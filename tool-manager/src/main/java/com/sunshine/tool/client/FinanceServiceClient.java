package com.sunshine.tool.client;

import com.sunshine.common.core.result.R;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class FinanceServiceClient {

    @Value("${finance.base-url:http://localhost:8710}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
        log.info("[FinanceServiceClient] baseUrl={}", baseUrl);
    }

    @SuppressWarnings("unchecked")
    public String listMessagesText(String status) {
        List<Map<String, Object>> messages = webClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/api/finance/messages");
                    if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                        b.queryParam("status", status);
                    }
                    return b.build();
                })
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {})
                .map(R::getData)
                .onErrorResume(e -> {
                    log.warn("[FinanceServiceClient] 调用失败: {}", e.getMessage());
                    return Mono.just(List.of());
                })
                .block();

        if (messages == null || messages.isEmpty()) {
            return "未查询到符合条件的财务消息。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(messages.size()).append(" 条财务消息：\n");
        for (Map<String, Object> msg : messages) {
            sb.append("- [").append(msg.get("id")).append("] ")
                    .append(msg.get("title"))
                    .append(" | 类型=").append(msg.get("type"))
                    .append(" | 状态=").append(msg.get("status"))
                    .append(" | 金额=").append(msg.get("amount"))
                    .append(" | 申请人=").append(msg.get("applicant"))
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
