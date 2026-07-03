package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.DocumentCatalogService;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.config.ConfigResolveMode;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.config.RagEvalProperties;
import com.sunshine.rag.config.RagStorageProperties;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.entity.EvalSuiteItemEntity;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import com.sunshine.rag.pipeline.PipelineSearchResult;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.EvalSuiteRepository;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import com.sunshine.rag.service.RetrievalService;
import com.sunshine.rag.storage.LocalRagStorageService;
import com.sunshine.rag.storage.RagStorageFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluateServiceFullTest {

    @Mock
    private KnowledgeRetrievalPipeline pipeline;
    @Mock
    private EffectiveConfigResolver effectiveConfigResolver;
    @Mock
    private EvalReportRepository evalReportRepository;
    @Mock
    private EvalJobRepository evalJobRepository;
    @Mock
    private EvalSuiteRepository evalSuiteRepository;
    @Mock
    private RagConfigVersionRepository configVersionRepository;
    @Mock
    private EvalAsyncRunner evalAsyncRunner;
    @Mock
    private EvalSuiteService evalSuiteService;
    @Mock
    private DocumentCatalogService documentCatalogService;
    @Mock
    private PythonEvalRunner pythonEvalRunner;

    private EvaluateService evaluateService;
    private GoldenSetLoader goldenSetLoader;
    private EvalReportWriter evalReportWriter;

    @BeforeEach
    void setUp() throws Exception {
        RagEvalProperties props = new RagEvalProperties();
        EvalSuiteConfigParser configParser = new EvalSuiteConfigParser(new ObjectMapper());
        Map<String, Object> config = configParser.defaultConfig();
        config.put("gates", Map.of());
        EvalSuiteEntity suiteEntity = new EvalSuiteEntity();
        suiteEntity.setId(1L);
        suiteEntity.setTenantId("default");
        suiteEntity.setSuiteKey(EvalSuiteKeys.REGRESSION);
        suiteEntity.setDisplayName("标准回归");
        suiteEntity.setKind("standard");
        suiteEntity.setFormat("json");
        suiteEntity.setConfigJson(configParser.write(config));
        lenient().when(evalSuiteService.requireSuite("default", EvalSuiteKeys.REGRESSION)).thenReturn(suiteEntity);
        EvalSuiteItemEntity q1 = new EvalSuiteItemEntity();
        q1.setItemKey("q001");
        q1.setQueryText("年假可以请几天");
        q1.setRelevantDocIdsJson("[\"leave-policy-v1\"]");
        q1.setCategory("leave");
        EvalSuiteItemEntity q2 = new EvalSuiteItemEntity();
        q2.setItemKey("q002");
        q2.setQueryText("病假需要什么证明材料");
        q2.setRelevantDocIdsJson("[\"leave-policy-v1\"]");
        q2.setCategory("leave");
        EvalSuiteItemEntity q3 = new EvalSuiteItemEntity();
        q3.setItemKey("q003");
        q3.setQueryText("请假要提前多久申请");
        q3.setRelevantDocIdsJson("[\"leave-policy-v1\"]");
        q3.setCategory("leave");
        lenient().when(evalSuiteService.loadItems(1L)).thenReturn(List.of(q1, q2, q3));
        lenient().when(documentCatalogService.listDocuments("default", "default"))
                .thenReturn(List.of(new DocumentSummary(
                        "leave-policy-v1", "公司请假流程规范", "text", "20260701110011", 1)));
        goldenSetLoader = new GoldenSetLoader(evalSuiteService, configParser, documentCatalogService);
        RagStorageProperties storageProperties = new RagStorageProperties();
        storageProperties.setType("local");
        LocalRagStorageService localStorage = new LocalRagStorageService(storageProperties);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.sunshine.rag.storage.MinioStorageService> minioProvider =
                mock(ObjectProvider.class);
        lenient().when(minioProvider.getIfAvailable()).thenReturn(null);
        RagStorageFacade storageFacade = new RagStorageFacade(storageProperties, minioProvider, localStorage);
        evalReportWriter = new EvalReportWriter(props, storageFacade, new ObjectMapper());
        @SuppressWarnings("unchecked")
        ObjectProvider<com.sunshine.rag.admin.config.ConfigVersionService> configVersionProvider =
                mock(ObjectProvider.class);
        evaluateService = new EvaluateService(
                pipeline,
                effectiveConfigResolver,
                goldenSetLoader,
                evalSuiteService,
                pythonEvalRunner,
                evalReportRepository,
                evalJobRepository,
                evalSuiteRepository,
                configVersionRepository,
                evalReportWriter,
                props,
                new ObjectMapper(),
                evalAsyncRunner,
                configVersionProvider);
    }

    @Test
    void runFullEvalComputesPerfectRecallWithMockHits() {
        EffectiveRagConfig config = new EffectiveRagConfig(0.48f, "hybrid+rerank", 60, 20, 0.25f, 1200);
        when(effectiveConfigResolver.resolve("default", "default", ConfigResolveMode.PRODUCTION, null))
                .thenReturn(com.sunshine.rag.admin.config.ConfigBundlePayload.toResolvedKbConfig(
                        com.sunshine.rag.admin.config.ConfigBundleTestFixtures.fullPayload()));
        when(pipeline.searchWithConfig(any(PipelineSearchRequest.class), any(EffectiveRagConfig.class)))
                .thenReturn(Mono.just(new PipelineSearchResult(
                        "q",
                        "q",
                        List.of(new RetrievalService.DocFragment("公司请假流程规范", "content", 0.9f)),
                        null)));
        Map<String, Object> report = evaluateService.runFullEval(
                "default", "default", "hybrid+rerank",
                ConfigResolveMode.PRODUCTION, null, EvalSuiteKeys.REGRESSION);
        @SuppressWarnings("unchecked")
        Map<String, Double> recallAtK = (Map<String, Double>) report.get("recall_at_k");
        assertThat(recallAtK.get("5")).isEqualTo(1.0);
        assertThat((Double) report.get("mrr")).isEqualTo(1.0);
        assertThat((Integer) report.get("query_count")).isEqualTo(3);
    }

    @Test
    void evalReportWriterPersistsJsonAndMarkdown() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.sunshine.rag.storage.MinioStorageService> minioProvider = mock(ObjectProvider.class);
        lenient().when(minioProvider.getIfAvailable()).thenReturn(null);
        EvalReportWriter writer = new EvalReportWriter(
                new RagEvalProperties(),
                new RagStorageFacade(
                        new RagStorageProperties(),
                        minioProvider,
                        new LocalRagStorageService(new RagStorageProperties())),
                new ObjectMapper());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_at", "2026-07-01T12:00:00");
        report.put("run_tag", "test-run");
        report.put("date", "2026-07-01");
        report.put("schema_version", 1);
        report.put("suite_key", EvalSuiteKeys.REGRESSION);
        report.put("strategy", "hybrid+rerank");
        report.put("query_count", 1);
        report.put("min_score", 0.48);
        report.put("recall_at_k", Map.of("3", 1.0, "5", 1.0));
        report.put("mrr", 1.0);
        report.put("empty_rate_positive", 0.0);
        report.put("empty_rate_negative", 0.0);
        report.put("latency_ms", Map.of("p50", 1.0, "p95", 2.0));
        report.put("gates", Map.of());
        var written = writer.writeLocal(report);
        assertThat(Files.exists(written.jsonPath())).isTrue();
        assertThat(Files.exists(written.mdPath())).isTrue();
        Files.deleteIfExists(written.jsonPath());
        Files.deleteIfExists(written.mdPath());
    }
}
