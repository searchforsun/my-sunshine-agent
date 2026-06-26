package com.sunshine.rag.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.MilvusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RagAdminControllerTest {

    @Mock
    private MilvusService milvusService;

    @Mock
    private ElasticsearchIndexService elasticsearchIndexService;

    @Test
    void rebuild_rejectsBadToken() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        RagAdminController controller = new RagAdminController(milvusService, elasticsearchIndexService, props);

        assertThatThrownBy(() -> controller.rebuild("wrong").block())
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(RagErrorCode.ADMIN_TOKEN_INVALID));
        verifyNoInteractions(milvusService);
    }

    @Test
    void rebuild_okWithValidToken() {
        RagAdminProperties props = new RagAdminProperties();
        props.setToken("secret");
        RagAdminController controller = new RagAdminController(milvusService, elasticsearchIndexService, props);

        R<Map<String, Object>> body = controller.rebuild("secret").block();

        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(200);
        assertThat(body.getData()).containsEntry("collection", "sunshine_knowledge");
        verify(milvusService).rebuildCollection();
        verify(elasticsearchIndexService).rebuildIndex();
    }
}
