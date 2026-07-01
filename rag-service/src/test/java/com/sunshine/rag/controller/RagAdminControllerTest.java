package com.sunshine.rag.controller;

import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.MilvusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RagAdminControllerTest {

    @Mock
    private MilvusService milvusService;

    @Mock
    private ElasticsearchIndexService elasticsearchIndexService;

    @Test
    void rebuild_triggersMilvusAndEsRebuild() {
        RagAdminController controller = new RagAdminController(milvusService, elasticsearchIndexService);
        controller.rebuild().block();
        verify(milvusService).rebuildCollection();
        verify(elasticsearchIndexService).rebuildIndex();
    }
}
