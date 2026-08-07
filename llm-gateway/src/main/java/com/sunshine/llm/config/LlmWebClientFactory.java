package com.sunshine.llm.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 构建 LLM 厂商 WebClient。dev 环境可开启 insecure-ssl 以绕过本机 JDK 信任链缺失（如企业代理证书）。
 * 统一设置 connect/response 超时：上游慢或挂起时快速失败，交由 ModelRouter 降级，而非无限等待。
 */
@Slf4j
@Component
public class LlmWebClientFactory {

    @Value("${llm.webclient.insecure-ssl:false}")
    private boolean insecureSsl;

    @Value("${llm.webclient.connect-timeout:10s}")
    private Duration connectTimeout;

    @Value("${llm.webclient.response-timeout:120s}")
    private Duration responseTimeout;

    @Value("${llm.webclient.read-idle-timeout:60s}")
    private Duration readIdleTimeout;

    public WebClient create(String baseUrl) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));

        // 读空闲超时（ReadTimeoutHandler）：SSE 流在两次 chunk 之间静默超过阈值即断开。
        // responseTimeout 是整体时长，对持续有数据的 SSE 不生效；上游半挂（不发数据也不结束）
        // 时若只有整体超时，前端会干等满 120s。读空闲超时让这类静默流快速失败交 ModelRouter 降级。
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
}
