package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.DocumentCatalogService;
import com.sunshine.rag.config.RagStorageProperties;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.entity.EvalSuiteItemEntity;
import com.sunshine.rag.repository.EvalSuiteItemRepository;
import com.sunshine.rag.repository.EvalSuiteRepository;
import com.sunshine.rag.storage.LocalRagStorageService;
import com.sunshine.rag.storage.RagStorageFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalSuiteServiceTest {

    @Mock
    private EvalSuiteRepository evalSuiteRepository;
    @Mock
    private EvalSuiteItemRepository evalSuiteItemRepository;
    @Mock
    private DocumentCatalogService documentCatalogService;

    private EvalSuiteService evalSuiteService;
    private EvalSuiteConfigParser configParser;

    @BeforeEach
    void setUp() {
        RagStorageProperties storageProperties = new RagStorageProperties();
        storageProperties.setType("local");
        LocalRagStorageService localStorage = new LocalRagStorageService(storageProperties);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.sunshine.rag.storage.MinioStorageService> minioProvider = mock(ObjectProvider.class);
        lenient().when(minioProvider.getIfAvailable()).thenReturn(null);
        RagStorageFacade storageFacade = new RagStorageFacade(storageProperties, minioProvider, localStorage);
        configParser = new EvalSuiteConfigParser(new ObjectMapper());
        evalSuiteService = new EvalSuiteService(
                evalSuiteRepository,
                evalSuiteItemRepository,
                storageFacade,
                configParser,
                new ObjectMapper());
    }

    @Test
    void createStandardSuitePersistsEntityAndLoaderCanRead() {
        when(evalSuiteRepository.findByTenantIdAndSuiteKey("default", "custom_v1")).thenReturn(Optional.empty());
        when(evalSuiteRepository.save(any(EvalSuiteEntity.class))).thenAnswer(inv -> {
            EvalSuiteEntity entity = inv.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        when(evalSuiteItemRepository.findBySuiteIdOrderBySortOrderAscItemKeyAsc(1L)).thenReturn(List.of(itemEntity()));
        var detail = evalSuiteService.create("default", new com.sunshine.rag.admin.eval.dto.EvalSuiteCreateRequest(
                "custom_v1", "Custom", "desc", "standard", null, null, null));
        assertThat(detail.suiteKey()).isEqualTo("custom_v1");
        assertThat(detail.kind()).isEqualTo("standard");
        ArgumentCaptor<EvalSuiteEntity> captor = ArgumentCaptor.forClass(EvalSuiteEntity.class);
        verify(evalSuiteRepository).save(captor.capture());
        EvalSuiteEntity saved = captor.getValue();
        when(evalSuiteRepository.findByTenantIdAndSuiteKey("default", "custom_v1")).thenReturn(Optional.of(saved));
        GoldenSetLoader loader = new GoldenSetLoader(evalSuiteService, configParser, documentCatalogService);
        GoldenSetLoader.GoldenSetData data = loader.load("default", "custom_v1");
        assertThat(data.queries()).hasSize(1);
        assertThat(data.queries().getFirst().query()).isEqualTo("test");
    }

    private static EvalSuiteItemEntity itemEntity() {
        EvalSuiteItemEntity item = new EvalSuiteItemEntity();
        item.setItemKey("q001");
        item.setQueryText("test");
        item.setRelevantDocIdsJson("[\"d1\"]");
        item.setCategory("core");
        return item;
    }
}
