package com.sunshine.tool.service;

import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(DbToolCatalogService.class)
@ActiveProfiles("test")
class DbToolCatalogServiceTest {

    @Autowired
    private DbToolCatalogService dbToolCatalogService;

    @Autowired
    private ToolDefinitionRepository toolDefinitionRepository;

    @Test
    void listCatalog_filtersTenantAndEnabled() {
        saveTool("sdk__sunshine-finance__list_my_expenses", "default", true);
        saveTool("sdk__sunshine-oa__list_oa_tasks", "default", false);
        saveTool("tenant_tool", "tenant-a", true);

        assertThat(dbToolCatalogService.listCatalog("default", true))
                .extracting(e -> e.id())
                .containsExactly("sdk__sunshine-finance__list_my_expenses");

        assertThat(dbToolCatalogService.listCatalog("tenant-a", true))
                .extracting(e -> e.id())
                .containsExactlyInAnyOrder("sdk__sunshine-finance__list_my_expenses", "tenant_tool");

        assertThat(dbToolCatalogService.listCatalog("default", false))
                .extracting(e -> e.id())
                .contains("sdk__sunshine-oa__list_oa_tasks");
    }

    @Test
    void listCatalog_mapsParametersFromSchemaJson() {
        saveTool("sdk__sunshine-finance__list_my_expenses", "default", true);
        var entry = dbToolCatalogService.listCatalog("default", true).getFirst();
        assertThat(entry.parameters()).containsKey("type");
        assertThat(entry.displayName()).isEqualTo("查询待审批财务消息");
        assertThat(entry.kind()).isEqualTo("remote");
        assertThat(entry.source()).isEqualTo("sdk");
        assertThat(entry.sourceRef()).isEqualTo("sunshine-finance");
        assertThat(entry.enabled()).isTrue();
    }

    @Test
    void listCatalog_includesDisabledWhenNotFiltered() {
        saveTool("sdk__sunshine-oa__list_oa_tasks", "default", false);
        var entry = dbToolCatalogService.listCatalog("default", false).stream()
                .filter(e -> e.id().equals("sdk__sunshine-oa__list_oa_tasks"))
                .findFirst()
                .orElseThrow();
        assertThat(entry.enabled()).isFalse();
    }

    private void saveTool(String id, String tenantId, boolean enabled) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(id);
        entity.setSource("sdk");
        entity.setSourceRef("sunshine-finance");
        entity.setExternalName(id);
        entity.setDisplayName("查询待审批财务消息");
        entity.setDescription("desc");
        entity.setSchemaJson(Map.of(
                "type", "object",
                "properties", Map.of("status", Map.of("type", "string"))));
        entity.setSchemaHash("abc");
        entity.setKind("remote");
        entity.setTenantId(tenantId);
        entity.setEnabled(enabled);
        toolDefinitionRepository.save(entity);
    }
}
