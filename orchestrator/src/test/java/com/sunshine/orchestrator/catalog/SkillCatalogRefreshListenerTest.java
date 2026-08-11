package com.sunshine.orchestrator.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SkillCatalogRefreshListenerTest {

    @Mock
    private SkillCatalogService skillCatalogService;

    private SkillCatalogRefreshListener listener;

    @BeforeEach
    void setUp() {
        listener = new SkillCatalogRefreshListener(skillCatalogService);
    }

    @Test
    void onMessage_refreshesSkillCatalog() {
        Message message = new DefaultMessage("skill-catalog-changed".getBytes(), "default".getBytes());
        listener.onMessage(message, null);
        verify(skillCatalogService).refresh();
    }
}
