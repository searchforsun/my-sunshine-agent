package com.sunshine.tool.sdk;

import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.repo.SdkApplicationRepository;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tools.sdk.dto.SdkToolCatalogResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(SdkCatalogUpsertService.class)
@ActiveProfiles("test")
class SdkCatalogUpsertServiceTest {

    @Autowired
    private SdkCatalogUpsertService upsertService;

    @Autowired
    private ToolDefinitionRepository toolDefinitionRepository;

    @Autowired
    private SdkApplicationRepository sdkApplicationRepository;

    @Test
    void upsert_createsToolWithDoubleUnderscoreId() {
        upsertService.upsert("sunshine-finance", "sunshine-finance", sampleCatalog("查询待审批财务消息"));

        Optional<ToolDefinitionEntity> tool = toolDefinitionRepository
                .findBySourceAndSourceRefAndExternalName("sdk", "sunshine-finance", "list_finance_messages");
        assertThat(tool).isPresent();
        assertThat(tool.get().getId()).isEqualTo("sdk__sunshine-finance__list_finance_messages");
        assertThat(tool.get().isIdValid()).isTrue();
        assertThat(tool.get().getSchemaHash()).isNotBlank();
        assertThat(sdkApplicationRepository.findById("sunshine-finance")).isPresent();
    }

    @Test
    void upsert_sameHash_preservesMetadataEditedDisplayName() {
        upsertService.upsert("sunshine-finance", "sunshine-finance", sampleCatalog("原始名称"));

        ToolDefinitionEntity entity = toolDefinitionRepository
                .findBySourceAndSourceRefAndExternalName("sdk", "sunshine-finance", "list_finance_messages")
                .orElseThrow();
        entity.setDisplayName("管理页覆盖名");
        entity.setMetadataEdited(true);
        toolDefinitionRepository.save(entity);

        upsertService.upsert("sunshine-finance", "sunshine-finance", sampleCatalog("SDK 新名称"));

        ToolDefinitionEntity updated = toolDefinitionRepository
                .findById("sdk__sunshine-finance__list_finance_messages").orElseThrow();
        assertThat(updated.getDisplayName()).isEqualTo("管理页覆盖名");
    }

    @Test
    void upsert_invalidExternalName_marksToolIllegal() {
        SdkToolCatalogResponse catalog = new SdkToolCatalogResponse(
                "sunshine-finance",
                "1.0.0-SNAPSHOT",
                1,
                List.of(new SdkToolCatalogResponse.ToolEntry(
                        "bad.tool",
                        "非法工具",
                        "desc",
                        "read",
                        "",
                        null,
                        Map.of("type", "object"))));
        upsertService.upsert("sunshine-finance", "sunshine-finance", catalog);

        ToolDefinitionEntity tool = toolDefinitionRepository
                .findBySourceAndSourceRefAndExternalName("sdk", "sunshine-finance", "bad.tool")
                .orElseThrow();
        assertThat(tool.isIdValid()).isFalse();
        assertThat(tool.isEnabled()).isFalse();
        assertThat(tool.getIdError()).isNotBlank();
    }

    @Test
    void upsert_metadataEdited_preservesTimelineFields() {
        upsertService.upsert("sunshine-finance", "sunshine-finance", sampleCatalog("查询待审批财务消息"));

        ToolDefinitionEntity entity = toolDefinitionRepository
                .findBySourceAndSourceRefAndExternalName("sdk", "sunshine-finance", "list_finance_messages")
                .orElseThrow();
        entity.setTimelineSummaryTemplate("{output}");
        entity.setTimelineSummaryExtract("{\"output\":\"line:0\"}");
        entity.setMetadataEdited(true);
        toolDefinitionRepository.save(entity);

        upsertService.upsert("sunshine-finance", "sunshine-finance", sampleCatalog("SDK 新名称"));

        ToolDefinitionEntity updated = toolDefinitionRepository
                .findById("sdk__sunshine-finance__list_finance_messages").orElseThrow();
        assertThat(updated.getTimelineSummaryTemplate()).isEqualTo("{output}");
        assertThat(updated.getTimelineSummaryExtract()).isEqualTo("{\"output\":\"line:0\"}");
    }

    @Test
    void upsert_replacesLegacyDotIdWithDoubleUnderscore() {
        ToolDefinitionEntity legacy = new ToolDefinitionEntity();
        legacy.setId("sdk.sunshine-finance.list_finance_messages");
        legacy.setSource("sdk");
        legacy.setSourceRef("sunshine-finance");
        legacy.setExternalName("list_finance_messages");
        legacy.setDisplayName("旧 ID 工具");
        legacy.setDescription("desc");
        legacy.setSchemaJson(Map.of("type", "object"));
        legacy.setSchemaHash("legacy-hash");
        legacy.setKind("remote");
        legacy.setTenantId("default");
        legacy.setEnabled(true);
        toolDefinitionRepository.save(legacy);

        upsertService.upsert("sunshine-finance", "sunshine-finance", sampleCatalog("查询待审批财务消息"));

        assertThat(toolDefinitionRepository.findById("sdk.sunshine-finance.list_finance_messages")).isEmpty();
        Optional<ToolDefinitionEntity> recreated = toolDefinitionRepository
                .findById("sdk__sunshine-finance__list_finance_messages");
        assertThat(recreated).isPresent();
        assertThat(recreated.get().getDisplayName()).isEqualTo("查询待审批财务消息");
        assertThat(recreated.get().isIdValid()).isTrue();
    }

    private SdkToolCatalogResponse sampleCatalog(String displayName) {
        return new SdkToolCatalogResponse(
                "sunshine-finance",
                "1.0.0-SNAPSHOT",
                1,
                List.of(new SdkToolCatalogResponse.ToolEntry(
                        "list_finance_messages",
                        displayName,
                        "按状态筛选",
                        "read",
                        "{count} 条财务消息",
                        "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("status", Map.of("type", "string"))))));
    }
}
