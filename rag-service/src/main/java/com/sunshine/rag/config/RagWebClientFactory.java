package com.sunshine.rag.config;

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

/** 构建 DashScope WebClient；dev 可开 insecure-ssl 绕过本机 JDK 信任链缺失。 */
@Slf4j
@Component
public class RagWebClientFactory {

    @Value("${rag.webclient.insecure-ssl:false}")
    private boolean insecureSsl;

    public WebClient create(String baseUrl) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        if (insecureSsl) {
            try {
                SslContext sslContext = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
                HttpClient httpClient = HttpClient.create().secure(spec -> spec.sslContext(sslContext));
                builder.clientConnector(new ReactorClientHttpConnector(httpClient));
                log.warn("[RAG] insecure-ssl=true，仅用于本地开发");
            } catch (SSLException e) {
                throw new IllegalStateException("Failed to build insecure SSL WebClient", e);
            }
        }
        return builder.build();
    }
}
