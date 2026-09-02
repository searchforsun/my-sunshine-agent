package com.sunshine.tool.sdk;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.config.ToolIntegrationProperties;
import com.sunshine.tool.entity.SdkApplicationEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.SdkApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SdkDiscoveryPuller {

    private static final String META_TOOL_APP = "sunshine.tool-app";
    private static final String META_TOOL_APP_ID = "sunshine.tool-app-id";

    private final DiscoveryClient discoveryClient;
    private final SdkCatalogClient sdkCatalogClient;
    private final SdkCatalogUpsertService sdkCatalogUpsertService;
    private final SdkApplicationRepository sdkApplicationRepository;
    private final ToolIntegrationProperties properties;

    @Scheduled(fixedDelayString = "${tool.sdk.pull-interval-seconds:60}000")
    public void scheduledPull() {
        pullAll();
    }

    public void pullAll() {
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.getSdk().getInvokeTimeoutSeconds()));
        Map<String, ServiceInstance> onlineApps = discoverToolAppInstances();
        Set<String> seenAppIds = new HashSet<>();

        for (Map.Entry<String, ServiceInstance> entry : onlineApps.entrySet()) {
            String appId = entry.getKey();
            ServiceInstance instance = entry.getValue();
            seenAppIds.add(appId);
            pullFromInstance(appId, instance.getServiceId(), timeout);
        }

        sdkApplicationRepository.findAll().forEach(app -> {
            if (!seenAppIds.contains(app.getId())) {
                app.setStatus("offline");
                app.setUpdatedAt(Instant.now());
                sdkApplicationRepository.save(app);
            }
        });
    }

    /** Admin 手动同步入口 */
    public void syncOne(String appId) {
        SdkApplicationEntity app = sdkApplicationRepository.findById(appId)
                .orElseThrow(() -> new BizException(ToolErrorCode.SDK_APP_NOT_FOUND));
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.getSdk().getInvokeTimeoutSeconds()));
        List<ServiceInstance> instances = filterToolAppInstances(discoveryClient.getInstances(app.getNacosService()));
        if (instances.isEmpty()) {
            app.setStatus("offline");
            app.setUpdatedAt(Instant.now());
            sdkApplicationRepository.save(app);
            throw new BizException(ToolErrorCode.SDK_APP_OFFLINE);
        }
        pullFromInstance(appId, app.getNacosService(), timeout);
    }

    private Map<String, ServiceInstance> discoverToolAppInstances() {
        Map<String, ServiceInstance> result = new HashMap<>();
        for (String service : discoveryClient.getServices()) {
            for (ServiceInstance instance : filterToolAppInstances(discoveryClient.getInstances(service))) {
                String appId = resolveAppId(instance);
                result.putIfAbsent(appId, instance);
            }
        }
        return result;
    }

    private List<ServiceInstance> filterToolAppInstances(List<ServiceInstance> instances) {
        return instances.stream()
                .filter(i -> "true".equalsIgnoreCase(i.getMetadata().get(META_TOOL_APP)))
                .toList();
    }

    private void pullFromInstance(String appId, String nacosService, Duration timeout) {
        var catalog = sdkCatalogClient.fetchCatalog(nacosService, "/sunshine/tools/catalog", timeout);
        if (catalog == null) {
            log.warn("[SdkDiscoveryPuller] empty catalog appId={} service={}", appId, nacosService);
            return;
        }
        sdkCatalogUpsertService.upsert(appId, nacosService, catalog);
        log.debug("[SdkDiscoveryPuller] synced appId={} tools={}", appId,
                catalog.tools() != null ? catalog.tools().size() : 0);
    }

    private String resolveAppId(ServiceInstance instance) {
        String appId = instance.getMetadata().get(META_TOOL_APP_ID);
        if (StringUtils.hasText(appId)) {
            return appId.strip();
        }
        return instance.getServiceId();
    }
}
