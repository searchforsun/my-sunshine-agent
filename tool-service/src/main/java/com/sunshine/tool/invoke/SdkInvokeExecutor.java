package com.sunshine.tool.invoke;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.config.ToolIntegrationProperties;
import com.sunshine.tool.entity.SdkApplicationEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.SdkApplicationRepository;
import com.sunshine.tools.sdk.dto.SdkToolInvokeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class SdkInvokeExecutor {

    private static final String META_TOOL_APP = "sunshine.tool-app";

    private final DiscoveryClient discoveryClient;
    private final SdkApplicationRepository sdkApplicationRepository;
    private final WebClient webClient;
    private final ToolIntegrationProperties properties;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public SdkInvokeExecutor(
            DiscoveryClient discoveryClient,
            SdkApplicationRepository sdkApplicationRepository,
            WebClient.Builder webClientBuilder,
            ToolIntegrationProperties properties) {
        this.discoveryClient = discoveryClient;
        this.sdkApplicationRepository = sdkApplicationRepository;
        this.webClient = webClientBuilder.build();
        this.properties = properties;
    }

    public String invoke(ToolDefinitionEntity tool, Map<String, String> params, String userId, String tenantId) {
        SdkApplicationEntity app = sdkApplicationRepository.findById(tool.getSourceRef())
                .orElseThrow(() -> new BizException(ToolErrorCode.SDK_APP_NOT_FOUND));
        ServiceInstance instance = pickInstance(app.getNacosService());
        if (instance == null) {
            throw new BizException(ToolErrorCode.SDK_APP_OFFLINE);
        }
        String invokePath = StringUtils.hasText(app.getInvokePath()) ? app.getInvokePath() : "/sunshine/tools/invoke";
        String url = "http://" + instance.getHost() + ":" + instance.getPort()
                + invokePath + "/" + tool.getExternalName();
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.getSdk().getInvokeTimeoutSeconds()));
        Map<String, String> body = params != null ? params : Map.of();

        WebClient.RequestBodySpec request = webClient.post().uri(url);
        if (StringUtils.hasText(userId)) {
            request = request.header("x-user-id", userId);
        }
        if (StringUtils.hasText(tenantId)) {
            request = request.header("x-tenant-id", tenantId);
        } else {
            request = request.header("x-tenant-id", "default");
        }
        SdkToolInvokeResponse response = request
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SdkToolInvokeResponse.class)
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.warn("[SdkInvokeExecutor] invoke failed tool={} url={}: {}", tool.getId(), url, e.getMessage());
                    return Mono.just(SdkToolInvokeResponse.failure(e.getMessage()));
                })
                .block();

        if (response == null) {
            throw new BizException(ToolErrorCode.SDK_INVOKE_FAILED);
        }
        if (!response.ok()) {
            log.warn("[SdkInvokeExecutor] tool error tool={}: {}", tool.getId(), response.error());
            throw new BizException(ToolErrorCode.SDK_INVOKE_FAILED);
        }
        return response.result() != null ? response.result() : "";
    }

    private ServiceInstance pickInstance(String nacosService) {
        List<ServiceInstance> instances = discoveryClient.getInstances(nacosService).stream()
                .filter(i -> "true".equalsIgnoreCase(i.getMetadata().get(META_TOOL_APP)))
                .toList();
        if (instances.isEmpty()) {
            return null;
        }
        int idx = Math.floorMod(roundRobin.getAndIncrement(), instances.size());
        return instances.get(idx);
    }
}
