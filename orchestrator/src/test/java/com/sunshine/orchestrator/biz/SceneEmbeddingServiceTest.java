package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 业务场景 embedding 回退（authority §2.1b/§2.1c/§5.5）单测：
 * 余弦相似度 · 状态过滤（activeOnly）· minScore 阈值 · 索引缓存注入。
 */
class SceneEmbeddingServiceTest {

    private BusinessContextProperties properties;
    private BizSceneCatalogClient catalogClient;
    private SceneEmbeddingService service;

    @BeforeEach
    void setUp() {
        properties = new BusinessContextProperties();
        properties.setEnabled(true);
        properties.getSceneEmbedding().setEnabled(true);
        catalogClient = mock(BizSceneCatalogClient.class);
        when(catalogClient.embeddingIndex()).thenReturn(List.of());
        service = new SceneEmbeddingService(properties, catalogClient);
        service.refreshIndex();
    }

    private static List<Float> vec(double x, double y) {
        return List.of((float) x, (float) y);
    }

    private void addEntry(String scene, String status, List<Float> vector, String description) {
        service.upsertEntry(new BizSceneCatalogClient.SceneIndexEntry(
                scene, description, vector, status, "manual", "default"));
    }

    @Test
    void cosine_sameVector_returnsOne() {
        assertThat(SceneEmbeddingService.cosine(vec(1, 0), vec(1, 0))).isEqualTo(1d);
    }

    @Test
    void cosine_orthogonal_returnsZero() {
        assertThat(SceneEmbeddingService.cosine(vec(1, 0), vec(0, 1))).isEqualTo(0d);
    }

    @Test
    void cosine_dimMismatch_returnsZero() {
        assertThat(SceneEmbeddingService.cosine(vec(1, 0), List.of(1f))).isEqualTo(0d);
    }

    @Test
    void searchVector_bestScoreAboveThreshold_returnsTopMatch() {
        addEntry("expense-assist", "active", vec(1, 0.2), "报销查询与提交");
        addEntry("travel-budget", "active", vec(1, 0.9), "差旅额度管控");
        List<SceneEmbeddingService.SceneMatch> matches = service.searchVector(vec(1, 0.1));
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).bizScene()).isEqualTo("expense-assist");
        assertThat(matches.get(0).score()).isGreaterThan(properties.getSceneEmbedding().getMinScore());
    }

    @Test
    void searchVector_pendingReview_retrievable() {
        addEntry("auto-scene", "pending_review", vec(1, 0.2), "待审核场景");
        List<SceneEmbeddingService.SceneMatch> matches = service.searchVector(vec(1, 0));
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).bizScene()).isEqualTo("auto-scene");
    }

    @Test
    void searchVector_disabledAndRejected_excluded() {
        addEntry("old-scene", "disabled", vec(1, 0.2), "已停用场景");
        addEntry("rejected-scene", "rejected", vec(1, 0.2), "被拒场景");
        assertThat(service.searchVector(vec(1, 0))).isEmpty();
    }

    @Test
    void searchVector_belowThreshold_excluded() {
        addEntry("policy-qa", "active", vec(1, 0), "制度问答");
        List<SceneEmbeddingService.SceneMatch> matches = service.searchVector(vec(0, 1));
        assertThat(matches).isEmpty();
    }

    @Test
    void searchVector_missingVector_skipped() {
        addEntry("expense-assist", "active", List.of(), "无向量场景");
        assertThat(service.searchVector(vec(1, 0))).isEmpty();
    }

    @Test
    void gateOff_returnsEmpty() {
        properties.setEnabled(false);
        assertThat(service.search("报销")).isEmpty();
        assertThat(service.enabled()).isFalse();
    }

    @Test
    void embed_missingApiKey_returnsNull() {
        properties.getSceneEmbedding().setApiKey("");
        assertThat(service.embed("报销查询")).isNull();
    }

    @Test
    void embed_inReactorEventLoopThread_returnsVector() throws Exception {
        String json = "{\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2,0.3]}]}}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            properties.getSceneEmbedding().setBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            properties.getSceneEmbedding().setApiKey("test-key");
            properties.getSceneEmbedding().setModel("text-embedding-v4");
            AtomicReference<List<Float>> holder = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    holder.set(service.embed("报销查询"));
                } catch (Throwable e) {
                    error.set(e);
                }
            }, "reactor-http-epoll-test");
            t.start();
            t.join(15_000);
            assertThat(error.get()).as("事件循环线程 block 不应抛 NonBlocking 异常").isNull();
            assertThat(holder.get()).containsExactly(0.1f, 0.2f, 0.3f);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void search_withoutApiKey_returnsEmpty() {
        properties.getSceneEmbedding().setApiKey("");
        addEntry("expense-assist", "active", vec(1, 0.2), "报销查询与提交");
        assertThat(service.search("我想查报销")).isEmpty();
    }
}
