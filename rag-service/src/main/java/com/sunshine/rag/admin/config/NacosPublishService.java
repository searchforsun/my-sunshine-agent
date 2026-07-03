package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.config.dto.NacosPublishResult;
import com.sunshine.rag.config.RagNacosProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 将 tenant 级 config draft publish 至 Nacos（同 scripts/sync_nacos.py） */
@Slf4j
@Service
public class NacosPublishService {

    private final RagNacosProperties nacosProperties;
    private final WebClient webClient;

    @Autowired
    public NacosPublishService(RagNacosProperties nacosProperties) {
        this(nacosProperties, buildWebClient(nacosProperties));
    }

    NacosPublishService(RagNacosProperties nacosProperties, WebClient webClient) {
        this.nacosProperties = nacosProperties;
        this.webClient = webClient;
    }

/** @deprecated T25 后业务配置 SSOT 为 DB；过渡期 per-scope 发布仍调用 */
@Deprecated
    public NacosPublishResult publish(String scope, Map<String, Object> payload) {
        ConfigScope configScope = ConfigScope.require(scope);
        String dataId = configScope.dataId();
        String group = nacosProperties.getGroup();
        String current = fetchConfig(dataId, group);
        String patched = NacosYamlPatcher.patch(current, configScope.nacosPath(), payload);
        postConfig(dataId, group, patched);
        boolean exported = exportWorkspaceCopy(dataId, patched);
        log.info("[RAG] Nacos publish scope={} dataId={} exported={}", scope, dataId, exported);
        return new NacosPublishResult(scope, dataId, group, exported);
    }

    private String fetchConfig(String dataId, String group) {
        String uri = UriComponentsBuilder.fromPath("/v1/cs/configs")
                .queryParam("dataId", dataId)
                .queryParam("group", group)
                .queryParam("username", nacosProperties.getUsername())
                .queryParam("password", nacosProperties.getPassword())
                .queryParamIfPresent("tenant", java.util.Optional.ofNullable(blankToNull(nacosProperties.getTenant())))
                .build()
                .toUriString();
        String body = webClient.get().uri(uri).retrieve().bodyToMono(String.class).block();
        return body != null ? body : "";
    }

    private void postConfig(String dataId, String group, String content) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("dataId", dataId);
        form.add("group", group);
        form.add("type", "yaml");
        form.add("content", content);
        form.add("username", nacosProperties.getUsername());
        form.add("password", nacosProperties.getPassword());
        if (StringUtils.hasText(nacosProperties.getTenant())) {
            form.add("tenant", nacosProperties.getTenant());
        }
        String response = webClient.post()
                .uri("/v1/cs/configs")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (response == null || !response.trim().equalsIgnoreCase("true")) {
            throw new IllegalStateException("Nacos publish 失败: " + response);
        }
    }

    private boolean exportWorkspaceCopy(String dataId, String content) {
        String exportDir = nacosProperties.getExportDir();
        if (!StringUtils.hasText(exportDir)) {
            return false;
        }
        try {
            Path dir = Path.of(exportDir.trim());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(dataId), content);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("写回本地 Nacos 副本失败: " + e.getMessage(), e);
        }
    }

    private static WebClient buildWebClient(RagNacosProperties props) {
        return WebClient.builder().baseUrl(normalizeServerAddr(props.getServerAddr())).build();
    }

    static String normalizeServerAddr(String serverAddr) {
        if (!StringUtils.hasText(serverAddr)) {
            return "http://127.0.0.1:8848/nacos";
        }
        return serverAddr.endsWith("/") ? serverAddr.substring(0, serverAddr.length() - 1) : serverAddr;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
