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
public class OaServiceClient {

    @Value("${oa.base-url:http://localhost:8700}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
        log.info("[OaServiceClient] baseUrl={}", baseUrl);
    }

    public String listTasksText(String status) {
        List<Map<String, Object>> tasks = webClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/api/oa/tasks");
                    if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                        b.queryParam("status", status);
                    }
                    return b.build();
                })
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {})
                .map(R::getData)
                .onErrorResume(e -> {
                    log.warn("[OaServiceClient] 调用失败: {}", e.getMessage());
                    return Mono.just(List.of());
                })
                .block();
        if (tasks == null || tasks.isEmpty()) {
            return "未查询到符合条件的 OA 待办。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(tasks.size()).append(" 条 OA 待办：\n");
        for (Map<String, Object> task : tasks) {
            sb.append("- [").append(task.get("id")).append("] ")
                    .append(task.get("title"))
                    .append(" | 分类=").append(task.get("category"))
                    .append(" | 状态=").append(task.get("status"))
                    .append(" | 处理人=").append(task.get("assignee"))
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
