package com.sunshine.orchestrator.agent.runtime;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 聊天图片交付转换：delivery-mode=url → URLSource（要求模型侧可回拉 MinIO 地址）；
 * base64（默认，模型侧无公网）→ 内网拉取字节后 Base64Source 注入。
 * 仅接受 allowed-url-prefix 前缀的 URL（SSRF 防护）；单图拉取失败降级占位文本，不中断消息。
 */
@Slf4j
@Component
public class ChatImageDeliveryResolver {

    /** 拉取失败 / 非白名单 URL 的占位文案；块序与 imageUrls 一一对应，不中断消息 */
    public static final String PLACEHOLDER_TEXT = "[图片加载失败]";

    private static final String DEFAULT_DELIVERY_MODE = "base64";

    private final String deliveryMode;
    private final String allowedPrefix;
    private final WebClient fetchClient;

    public ChatImageDeliveryResolver(
            @Value("${chat-image.delivery-mode:base64}") String deliveryMode,
            @Value("${chat-image.allowed-url-prefix:}") String allowedPrefix) {
        this.deliveryMode = normalizeMode(deliveryMode);
        this.allowedPrefix = allowedPrefix == null ? "" : allowedPrefix.strip();
        // 直建 WebClient：容器内的 Builder 是 @LoadBalanced 单例（host 会被当 Nacos 服务名解析），
        // MinIO 是内网直连地址，不走服务发现
        this.fetchClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(10))))
                .build();
    }

    /** 组装前转换；产出块与 imageUrls 一一对应（失败图占位，保证块序稳定） */
    public Flux<ContentBlock> resolveImageBlocks(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return Flux.empty();
        }
        if ("url".equals(deliveryMode)) {
            return Flux.fromIterable(imageUrls).concatMap(this::toUrlBlock);
        }
        return Flux.fromIterable(imageUrls).concatMap(this::fetchAsBase64Block);
    }

    private Flux<ContentBlock> toUrlBlock(String url) {
        if (!isAllowed(url)) {
            log.warn("[ChatImage] 拒绝非白名单图片 URL: {}", url);
            return Flux.just(placeholder());
        }
        return Flux.just(ImageBlock.builder().source(new URLSource(url)).build());
    }

    private Flux<ContentBlock> fetchAsBase64Block(String url) {
        if (!isAllowed(url)) {
            log.warn("[ChatImage] 拒绝非白名单图片 URL: {}", url);
            return Flux.just(placeholder());
        }
        return fetchClient.get().uri(url).retrieve()
                .toEntity(byte[].class)
                .map(resp -> {
                    String mediaType = resp.getHeaders().getContentType() != null
                            ? resp.getHeaders().getContentType().toString()
                            : "image/png";
                    String base64 = Base64.getEncoder().encodeToString(resp.getBody());
                    return (ContentBlock) ImageBlock.builder()
                            .source(new Base64Source(mediaType, base64)).build();
                })
                .onErrorResume(e -> {
                    log.warn("[ChatImage] 图片拉取失败 url={}: {}", url, e.getMessage());
                    return Mono.just(placeholder());
                })
                .flux();
    }

    private boolean isAllowed(String url) {
        return allowedPrefix.isEmpty() || (url != null && url.startsWith(allowedPrefix));
    }

    private static ContentBlock placeholder() {
        return TextBlock.builder().text(PLACEHOLDER_TEXT).build();
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return DEFAULT_DELIVERY_MODE;
        }
        String normalized = mode.strip().toLowerCase();
        return normalized.isEmpty() ? DEFAULT_DELIVERY_MODE : normalized;
    }
}
