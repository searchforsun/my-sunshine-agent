package com.sunshine.bff.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import com.sunshine.common.web.RemoteErrorMapper;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 聊天图片上传：透传 resource-manager /api/chat/images */
@Slf4j
@Component
public class ChatImageClient {

    private final WebClient webClient;

    public ChatImageClient(WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl("http://sunshine-resource-manager")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        log.info("[BFF] ChatImage 客户端: baseUrl=http://sunshine-resource-manager");
    }

    public Mono<Map<String, Object>> upload(FilePart file, String userId, String tenantId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.asyncPart("file", file.content(), org.springframework.core.io.buffer.DataBuffer.class)
                .filename(file.filename())
                .contentType(file.headers().getContentType());
        return webClient.post()
                .uri("/api/chat/images")
                .headers(h -> {
                    if (StringUtils.hasText(userId)) {
                        h.set("x-user-id", userId);
                    }
                    // objectKey 按租户分目录，tenantId 必须透传
                    h.set("x-tenant-id", tenantId);
                })
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<? extends Throwable> toBizError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(RemoteErrorMapper.fromBody(response.statusCode().value(), body)));
    }
}
