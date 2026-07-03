package com.sunshine.rag.admin.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.sunshine.rag.admin.config.dto.NacosPublishResult;
import com.sunshine.rag.config.RagNacosProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class NacosPublishServiceTest {

    private WireMockServer nacosServer;
    private NacosPublishService publishService;

    @BeforeEach
    void setUp() {
        nacosServer = new WireMockServer(wireMockConfig().dynamicPort());
        nacosServer.start();
        WireMock.configureFor("localhost", nacosServer.port());
        RagNacosProperties props = new RagNacosProperties();
        props.setServerAddr("http://localhost:" + nacosServer.port() + "/nacos");
        props.setUsername("nacos");
        props.setPassword("nacos");
        props.setGroup("DEFAULT_GROUP");
        WebClient webClient = WebClient.builder().baseUrl(NacosPublishService.normalizeServerAddr(props.getServerAddr())).build();
        publishService = new NacosPublishService(props, webClient);
    }

    @AfterEach
    void tearDown() {
        if (nacosServer != null) {
            nacosServer.stop();
        }
    }

    @Test
    void publishPatchesMinScoreAndPostsToNacos() {
        String sample = """
                rag:
                  search:
                    min-score: 0.48
                    strategy: hybrid+rerank
                """;
        nacosServer.stubFor(get(urlPathEqualTo("/nacos/v1/cs/configs"))
                .willReturn(aResponse().withStatus(200).withBody(sample)));
        nacosServer.stubFor(post(urlPathEqualTo("/nacos/v1/cs/configs"))
                .willReturn(aResponse().withStatus(200).withBody("true")));

        NacosPublishResult result = publishService.publish("rag-search", Map.of("minScore", 0.55));

        assertThat(result.dataId()).isEqualTo("sunshine-rag.yaml");
        assertThat(result.scope()).isEqualTo("rag-search");
        nacosServer.verify(getRequestedFor(urlPathEqualTo("/nacos/v1/cs/configs"))
                .withQueryParam("dataId", equalTo("sunshine-rag.yaml"))
                .withQueryParam("group", equalTo("DEFAULT_GROUP")));
        nacosServer.verify(postRequestedFor(urlPathEqualTo("/nacos/v1/cs/configs"))
                .withRequestBody(containing("min-score"))
                .withRequestBody(containing("0.55")));
    }

    @Test
    void exportDirWritesWorkspaceCopy() throws Exception {
        Path exportDir = Files.createTempDirectory("nacos-export");
        String sample = """
                rag:
                  chunk:
                    max-size: 1200
                """;
        nacosServer.stubFor(get(urlPathEqualTo("/nacos/v1/cs/configs"))
                .willReturn(aResponse().withStatus(200).withBody(sample)));
        nacosServer.stubFor(post(urlPathEqualTo("/nacos/v1/cs/configs"))
                .willReturn(aResponse().withStatus(200).withBody("true")));

        RagNacosProperties props = new RagNacosProperties();
        props.setServerAddr("http://localhost:" + nacosServer.port() + "/nacos");
        props.setExportDir(exportDir.toString());
        WebClient webClient = WebClient.builder().baseUrl(NacosPublishService.normalizeServerAddr(props.getServerAddr())).build();
        NacosPublishService service = new NacosPublishService(props, webClient);

        NacosPublishResult result = service.publish("rag-chunk", Map.of("maxSize", 900));

        assertThat(result.workspaceExported()).isTrue();
        Path exported = exportDir.resolve("sunshine-rag.yaml");
        assertThat(Files.exists(exported)).isTrue();
        assertThat(Files.readString(exported)).contains("max-size: 900");
    }
}

class NacosYamlPatcherTest {

    @Test
    void patchConvertsCamelCaseToKebabCase() {
        String yaml = """
                rag:
                  search:
                    min-score: 0.48
                """;
        String patched = NacosYamlPatcher.patch(yaml, "rag.search", Map.of("minScore", 0.55, "rrfK", 80));
        assertThat(patched).contains("min-score: 0.55");
        assertThat(patched).contains("rrf-k: 80");
    }

    @Test
    void patchCreatesMissingPath() {
        String patched = NacosYamlPatcher.patch("", "rag.rewrite.empty-recall", Map.of("enabled", false));
        assertThat(patched).contains("empty-recall:");
        assertThat(patched).contains("enabled: false");
    }
}
