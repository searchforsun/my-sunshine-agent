package com.sunshine.rag.controller;

import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.service.MilvusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RagAdminControllerTest {

    @Mock
    private MilvusService milvusService;

    @Test
    void rebuild_rejectsBadToken() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        RagAdminController controller = new RagAdminController(milvusService, props);

        Map<String, Object> body = controller.rebuild("wrong").block();

        assertThat(body).containsEntry("code", 403);
        verifyNoInteractions(milvusService);
    }

    @Test
    void rebuild_okWithValidToken() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        RagAdminController controller = new RagAdminController(milvusService, props);

        Map<String, Object> body = controller.rebuild("secret").block();

        assertThat(body).containsEntry("code", 200);
        assertThat(body).containsEntry("collection", "sunshine_knowledge");
        verify(milvusService).rebuildCollection();
    }
}
