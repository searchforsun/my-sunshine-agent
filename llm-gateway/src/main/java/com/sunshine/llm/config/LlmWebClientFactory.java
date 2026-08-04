package com.sunshine.llm.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.time.Duration;

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

    public WebClient create(String baseUrl) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024));

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);

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
