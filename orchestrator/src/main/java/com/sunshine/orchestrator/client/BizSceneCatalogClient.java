package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 业务场景 Lab active 码闭集缓存（K2）：biz_scene 解析的合法码校验。
 * <p>active 码为低频数据：启动预加载 + 定时刷新，请求路径只读 volatile 缓存，避免在
 * reactor 非阻塞线程上 block()（被 Reactor 拒绝后会清空闭集）。
 */
@Slf4j
@Component
public class BizSceneCatalogClient {

    private final WebClient webClient;
    private volatile Set<String> activeCodes = Set.of();

    public BizSceneCatalogClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
    }

    @PostConstruct
    void warmUp() {
        refresh();
    }

    /** 定时刷新 active 码闭集（biz_scene 校验低频数据，无需高频率） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void scheduledRefresh() {
        refresh();
    }

    /** 只读缓存快照；refresh 由启动预加载与定时任务负责，不在请求路径触发 */
    public Set<String> activeCodes() {
        return activeCodes;
    }

    /** 拉取失败保留旧缓存（瞬时故障不清空闭集），仅记录告警 */
    public synchronized void refresh() {
        try {
            List<String> codes = webClient.get()
                    .uri("/api/biz-scenes/active-codes")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<String>>>() {})
                    .map(R::getData)
                    .block(Duration.ofSeconds(5));
            if (codes != null) {
                this.activeCodes = Set.copyOf(codes);
            }
        } catch (Exception e) {
            log.warn("[BizSceneCatalogClient] fetch active codes error: {}", e.getMessage());
        }
    }
}
