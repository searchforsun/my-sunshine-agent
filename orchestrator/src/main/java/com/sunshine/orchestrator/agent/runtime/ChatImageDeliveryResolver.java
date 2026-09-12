package com.sunshine.orchestrator.agent.runtime;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 聊天图片交付转换：delivery-mode=url → URLSource（要求模型侧可回拉 MinIO 地址）；
 * base64（默认，模型侧无公网）→ 内网拉取字节后 Base64Source 注入。
 * 仅接受白名单基址同 host:port 的图片 URL（URI 结构化校验、忽略 userinfo，SSRF 防护）；
 * 单图拉取失败降级占位文本，不中断消息。
 */
@Slf4j
@Component
public class ChatImageDeliveryResolver {

    /** 拉取失败 / 非白名单 URL 的占位文案；块序与 imageUrls 一一对应，不中断消息 */
    public static final String PLACEHOLDER_TEXT = "[图片加载失败]";

    private static final String DEFAULT_DELIVERY_MODE = "base64";

    private final String deliveryMode;
    /** 白名单基址解析出的 scheme://host:port 规范形；null = 基址非法/为空，全拒绝 */
    private final String allowedHostPort;
    private final WebClient fetchClient;

    @Autowired
    public ChatImageDeliveryResolver(
            @Value("${chat-image.delivery-mode:base64}") String deliveryMode,
            @Value("${chat-image.allowed-url-prefix:}") String allowedPrefix) {
        this(deliveryMode, allowedPrefix,
                HttpClient.create()
                        // 响应超时 5s + 连接超时 3s：与组装端 block(15s) 配套——
                        // 4 张并发 2 = 2 批 × 5s + 余量 < 15s，确保超时走占位降级而非中断整条消息
                        .responseTimeout(Duration.ofSeconds(5))
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000));
    }

    /** 测试直连构造器：注入自定义 HttpClient 便于本地 HttpServer 场景 */
    public ChatImageDeliveryResolver(String deliveryMode, String allowedPrefix, HttpClient httpClient) {
        this.deliveryMode = normalizeMode(deliveryMode);
        // 白名单以 URI 结构化解析为准（host:port 精确匹配）；基址非法时置 null = 全拒绝
        this.allowedHostPort = parseHostPort(allowedPrefix == null ? "" : allowedPrefix.strip());
        // 直建 WebClient：容器内的 Builder 是 @LoadBalanced 单例（host 会被当 Nacos 服务名解析），
        // MinIO 是内网直连地址，不走服务发现
        this.fetchClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** 组装前转换；产出块与 imageUrls 一一对应（失败图占位，保证块序稳定） */
    public Flux<ContentBlock> resolveImageBlocks(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return Flux.empty();
        }
        // 并发 2 与组装端 block(15s) 配套：4 张 = 2 批 × 单图 5s 超时 + 余量 < 15s；
        // flatMapSequential 保序输出（普通 flatMap 按完成时间乱序，会破坏块序契约）
        return Flux.fromIterable(imageUrls).flatMapSequential(this::toBlock, 2);
    }

    private Flux<ContentBlock> toBlock(String url) {
        if ("url".equals(deliveryMode)) {
            return toUrlBlock(url);
        }
        return fetchAsBase64Block(url);
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

    /**
     * 白名单判定：URI 结构化解析后按 scheme + host:port 精确比较，忽略 userinfo——
     * 字符串前缀匹配可被 {@code http://ecs4c16g:9000@internal.host/x.png} 的 userinfo 手法绕过。
     */
    private boolean isAllowed(String url) {
        if (allowedHostPort == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return false;
            }
            int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
            return (scheme.toLowerCase() + "://" + host.toLowerCase() + ":" + port).equals(allowedHostPort);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** 白名单基址 → scheme://host:port 规范形（端口缺省按 scheme 补全）；解析失败返回 null = 全拒绝 */
    private static String parseHostPort(String base) {
        if (base.isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(base);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
            return scheme.toLowerCase() + "://" + host.toLowerCase() + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
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
