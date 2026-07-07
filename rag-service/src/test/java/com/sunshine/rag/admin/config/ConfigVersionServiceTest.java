package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.entity.KnowledgeBaseEntity;
import com.sunshine.rag.entity.RagConfigBundleEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.KnowledgeBaseRepository;
import com.sunshine.rag.repository.RagConfigBundleRepository;
import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigVersionServiceTest {

    @Mock
    private RagConfigBundleRepository bundleRepository;
    @Mock
    private RagConfigVersionRepository versionRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private EvalJobRepository evalJobRepository;
    @Mock
    private EvalReportRepository evalReportRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ConfigVersionService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ConfigVersionStore store = new ConfigVersionStore(
                bundleRepository, versionRepository, knowledgeBaseRepository, objectMapper);
        ConfigVersionEvalLifecycle evalLifecycle = new ConfigVersionEvalLifecycle(versionRepository, store);
        ConfigVersionPublishOps publishOps = new ConfigVersionPublishOps(
                bundleRepository,
                versionRepository,
                evalJobRepository,
                evalReportRepository,
                store,
                evalLifecycle,
                eventPublisher);
        service = new ConfigVersionService(
                versionRepository,
                evalJobRepository,
                evalReportRepository,
                store,
                evalLifecycle,
                publishOps);
    }

    @Test
    void submitEval_onlyMarksPendingEval() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity draft = stubDraft(100L, "{\"search\":{\"minScore\":0.48}}");
        when(bundleRepository.findByTenantIdAndKbIdForUpdate("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findById(100L)).thenReturn(Optional.of(draft));
        when(versionRepository.findByBundleIdAndStatus(10L, ConfigVersionStatus.EVALUATING)).thenReturn(List.of());
        var result = service.submitEval("default", "finance");
        assertThat(result.status()).isEqualTo(ConfigVersionStatus.PENDING_EVAL);
        assertThat(draft.getStatus()).isEqualTo(ConfigVersionStatus.PENDING_EVAL);
    }

    @Test
    void beginEvaluating_movesEvalFailedToEvaluating() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity failed = stubVersion(100L, 2, ConfigVersionStatus.EVAL_FAILED, "{}");
        when(bundleRepository.findByTenantIdAndKbIdForUpdate("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findByIdAndBundleId(100L, 10L)).thenReturn(Optional.of(failed));
        when(versionRepository.findByBundleIdAndStatus(10L, ConfigVersionStatus.EVALUATING)).thenReturn(List.of());
        service.beginEvaluating("default", "finance", 100L);
        assertThat(failed.getStatus()).isEqualTo(ConfigVersionStatus.EVALUATING);
        verify(versionRepository).save(failed);
    }

    @Test
    void beginEvaluating_movesPendingToEvaluating() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity pending = stubVersion(100L, 2, ConfigVersionStatus.PENDING_EVAL, "{}");
        when(bundleRepository.findByTenantIdAndKbIdForUpdate("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findByIdAndBundleId(100L, 10L)).thenReturn(Optional.of(pending));
        when(versionRepository.findByBundleIdAndStatus(10L, ConfigVersionStatus.EVALUATING)).thenReturn(List.of());
        service.beginEvaluating("default", "finance", 100L);
        assertThat(pending.getStatus()).isEqualTo(ConfigVersionStatus.EVALUATING);
        verify(versionRepository).save(pending);
    }

    @Test
    void completeEvalFromJob_setsPassedOrFailed() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity evaluating = stubVersion(100L, 2, ConfigVersionStatus.EVALUATING, "{}");
        when(bundleRepository.findByTenantIdAndKbIdForUpdate("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findByIdAndBundleId(100L, 10L)).thenReturn(Optional.of(evaluating));
        service.completeEvalFromJob("default", "finance", 100L, 900L, true);
        assertThat(evaluating.getStatus()).isEqualTo(ConfigVersionStatus.EVAL_PASSED);
        assertThat(evaluating.getPublishEvalJobId()).isEqualTo(900L);
    }

    @Test
    void submitEval_rejectsWhenEvaluatingExists() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity evaluating = stubVersion(102L, 3, ConfigVersionStatus.EVALUATING, "{}");
        when(bundleRepository.findByTenantIdAndKbIdForUpdate("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findByBundleIdAndStatus(10L, ConfigVersionStatus.EVALUATING))
                .thenReturn(List.of(evaluating));
        assertThatThrownBy(() -> service.submitEval("default", "finance"))
                .isInstanceOf(ConfigVersionConflictException.class);
    }

    @Test
    void applySuggestions_evalFailed_becomesDraft() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity failed = stubVersion(100L, 2, ConfigVersionStatus.EVAL_FAILED, "{\"search\":{\"minScore\":0.48}}");
        when(bundleRepository.findByTenantIdAndKbIdForUpdate("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findByIdAndBundleId(100L, 10L)).thenReturn(Optional.of(failed));
        when(versionRepository.findByBundleIdAndStatus(10L, ConfigVersionStatus.EVALUATING)).thenReturn(List.of());
        var suggestions = List.of(new ConfigSuggestionItem("search.minScore", 0.48, 0.42, "lower threshold"));
        var result = service.applySuggestions("default", "finance", suggestions, 100L);
        assertThat(result).containsEntry("search", Map.of("minScore", 0.42));
        assertThat(failed.getStatus()).isEqualTo(ConfigVersionStatus.DRAFT);
        assertThat(bundle.getDraftVersionId()).isEqualTo(100L);
        verify(versionRepository).save(failed);
    }

    @Test
    void getDraftView_noDraft_throwsConflict() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, null, 101L);
        when(bundleRepository.findByTenantIdAndKbId("default", "finance")).thenReturn(Optional.of(bundle));
        assertThatThrownBy(() -> service.getDraftView("default", "finance"))
                .isInstanceOf(ConfigVersionConflictException.class)
                .hasMessageContaining("复制为草稿");
    }

    @Test
    void getDraftView_returnsDraftPayload() {
        stubKbExists();
        RagConfigBundleEntity bundle = stubBundle(10L, 100L, 101L);
        RagConfigVersionEntity draft = stubVersion(100L, 2, ConfigVersionStatus.DRAFT, "{\"search\":{\"minScore\":0.5}}");
        RagConfigVersionEntity active = stubVersion(101L, 1, ConfigVersionStatus.ACTIVE, "{}");
        when(bundleRepository.findByTenantIdAndKbId("default", "finance")).thenReturn(Optional.of(bundle));
        when(versionRepository.findById(100L)).thenReturn(Optional.of(draft));
        when(versionRepository.findById(101L)).thenReturn(Optional.of(active));
        var view = service.getDraftView("default", "finance");
        assertThat(view.draftVersionId()).isEqualTo(100L);
        assertThat(view.draftVersionNo()).isEqualTo(2);
        assertThat(view.payload()).containsEntry("search", Map.of("minScore", 0.5));
    }

    private void stubKbExists() {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId("default");
        kb.setKbId("finance");
        when(knowledgeBaseRepository.findByTenantIdAndKbId("default", "finance")).thenReturn(Optional.of(kb));
    }

    private RagConfigBundleEntity stubBundle(Long bundleId, Long draftId, Long activeId) {
        RagConfigBundleEntity bundle = new RagConfigBundleEntity();
        bundle.setId(bundleId);
        bundle.setTenantId("default");
        bundle.setKbId("finance");
        bundle.setDraftVersionId(draftId);
        bundle.setActivePublishedVersionId(activeId);
        return bundle;
    }

    private RagConfigVersionEntity stubDraft(Long id, String json) {
        return stubVersion(id, 2, ConfigVersionStatus.DRAFT, json);
    }

    private RagConfigVersionEntity stubVersion(Long id, int versionNo, String status, String json) {
        RagConfigVersionEntity version = new RagConfigVersionEntity();
        version.setId(id);
        version.setBundleId(10L);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setPayloadJson(json);
        return version;
    }
}
