package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.ToolCatalogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 触发集统一正文渲染：完整正文（# 技能·名 + 正文 + 声明工具 schema）与工具集收敛逻辑 */
@ExtendWith(MockitoExtension.class)
class SkillBodyRendererTest {

    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private ToolCatalogService toolCatalogService;
    @Mock
    private ToolSetResolver toolSetResolver;

    @InjectMocks
    private SkillBodyRenderer renderer;

    private static SkillCatalogEntry entry(String id, String overlay, String toolsJson) {
        return new SkillCatalogEntry(id, "技能" + id, "描述", overlay,
                toolsJson, 1, true, null, null, "all", null, "default");
    }

    @Test
    void renderLoadedSkill_includesNameBodyAndDeclaredToolSchema() {
        when(toolSetResolver.resolveDefaultTools("default", "chat"))
                .thenReturn(List.of("sdk__biz__list_expenses"));
        when(toolCatalogService.find("sdk__biz__list_expenses")).thenReturn(Optional.of(
                new ToolCatalogEntry("sdk__biz__list_expenses", "查询报销单", "列出报销单",
                        "chat", "SDK", null, null, null, Map.of(), null, false, true, true, null)));

        SkillCatalogEntry skill = entry("finance-analysis", "按四步法分析财务数据。",
                "[\"sdk__biz__list_expenses\"]");

        String body = renderer.renderLoadedSkill(skill, "default", "chat");

        assertThat(body)
                .contains("# 技能 finance-analysis · 技能finance-analysis")
                .contains("按四步法分析财务数据。")
                .contains("sdk__biz__list_expenses")
                .contains("查询报销单")
                .contains("列出报销单");
    }

    @Test
    void renderLoadedSkill_marksDeclaredToolOutsideCurrentSetNotCallable() {
        when(toolSetResolver.resolveDefaultTools("default", "chat")).thenReturn(List.of("other_tool"));

        String body = renderer.renderLoadedSkill(
                entry("finance-analysis", "正文", "[\"sdk__biz__list_expenses\"]"),
                "default", "chat");

        assertThat(body).contains("sdk__biz__list_expenses：不在当前会话工具集内，不可调用");
    }

    @Test
    void renderLoadedSkill_noDeclaredTools_omitsSchema() {
        String body = renderer.renderLoadedSkill(entry("plain", "无工具正文", "[]"), "default", "chat");
        assertThat(body).contains("无工具正文").doesNotContain("## 声明工具");
    }

    @Test
    void renderById_missingOrDisabled_returnsEmpty() {
        when(skillCatalogService.find("missing")).thenReturn(Optional.empty());
        assertThat(renderer.renderById("missing", "default", "chat")).isEmpty();

        when(skillCatalogService.find("off")).thenReturn(Optional.of(
                new SkillCatalogEntry("off", "停用", "", "正文", "[]", 1, false,
                        null, null, "all", null, "default")));
        assertThat(renderer.renderById("off", "default", "chat")).isEmpty();
    }

    @Test
    void renderById_nullId_returnsEmpty() {
        assertThat(renderer.renderById(null, "default", "chat")).isEmpty();
        assertThat(renderer.renderById(" ", "default", "chat")).isEmpty();
    }
}
