package com.sunshine.llm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 按 baseUrl 缓存 LLM 厂商 WebClient；provider 变更时 invalidate 强制重建。
 */
@Slf4j
@Component
public class LlmWebClientFactory {

    @Value("${llm.webclient.insecure-ssl:false}")
    private boolean insecureSsl;

    @Value("${llm.webclient.connect-timeout:10s}")
    private Duration connectTimeout = Duration.ofSeconds(10);

    @Value("${llm.webclient.response-timeout:120s}")
    private Duration responseTimeout = Duration.ofSeconds(120);

    @Value("${llm.webclient.read-idle-timeout:60s}")
    private Duration readIdleTimeout = Duration.ofSeconds(60);

    private final Map<String, WebClient> cacheByBaseUrl = new ConcurrentHashMap<>();

    public WebClient create(String baseUrl) {
        String key = normalizeBaseUrl(baseUrl);
        return cacheByBaseUrl.computeIfAbsent(key, this::build);
    }

    /** provider baseUrl 变更后清缓存，避免继续打旧上游 */
    public void invalidateAll() {
        cacheByBaseUrl.clear();
        log.info("[LLM-GW] WebClient cache invalidated");
    }

    public void invalidate(String baseUrl) {
        if (baseUrl != null) {
            cacheByBaseUrl.remove(normalizeBaseUrl(baseUrl));
        }
    }

    private WebClient build(String baseUrl) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));
        // 读空闲超时：SSE 两次 chunk 间静默过久即断开，交 ModelRouter 降级
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(readIdleTimeout.toMillis(), TimeUnit.MILLISECONDS)));
        if (insecureSsl) {
            try {
                SslContext sslContext = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
                httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
                log.warn("[LLM-GW] insecure-ssl=true，仅用于本地开发");
            } catch (SSLException e) {
                throw new IllegalStateException("Failed to build insecure SSL WebClient", e);
            }
        }
        builder.clientConnector(new ReactorClientHttpConnector(httpClient));
        return builder.build();
    }

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
