package com.sunshine.tool.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.tool.admin.ToolPatchRequest;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.mcp.McpSyncService;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.repo.SdkApplicationRepository;
import com.sunshine.tool.sdk.SdkDiscoveryPuller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(ToolsAdminController.class)
@ActiveProfiles("test")
class ToolsAdminPatchToolTest {

    @Autowired
    private ToolsAdminController toolsAdminController;

    @Autowired
    private ToolDefinitionRepository toolDefinitionRepository;

    @MockBean
    private SdkApplicationRepository sdkApplicationRepository;

    @MockBean
    private SdkDiscoveryPuller sdkDiscoveryPuller;

    @MockBean
    private McpServerAdminService mcpServerAdminService;

    @MockBean
    private McpSyncService mcpSyncService;

    @MockBean
    private ToolSetMemberService toolSetMemberService;

    @BeforeEach
    void seedTool() {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId("sdk__sunshine-finance__list_finance_messages");
        tool.setSource("sdk");
        tool.setSourceRef("sunshine-finance");
        tool.setExternalName("list_finance_messages");
        tool.setDisplayName("查询待审批财务消息");
        tool.setDescription("列出财务待办");
        tool.setKind("remote");
        tool.setSchemaJson(Map.of("type", "object"));
        tool.setSideEffect("read");
        tool.setTenantId("default");
        tool.setEnabled(true);
        toolDefinitionRepository.save(tool);
    }

    @Test
    void patchTool_rejectsBlankDescription() {
        assertThatThrownBy(() -> toolsAdminController.patchTool(
                "sdk__sunshine-finance__list_finance_messages",
                new ToolPatchRequest(null, null, "   ", null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工具描述不能为空");
    }

    @Test
    void patchTool_trimsDescription() {
        var response = toolsAdminController.patchTool(
                "sdk__sunshine-finance__list_finance_messages",
                new ToolPatchRequest(null, null, "  更新后的描述  ", null, null, null));
        assertThat(response.getData().getDescription()).isEqualTo("更新后的描述");
    }
}
