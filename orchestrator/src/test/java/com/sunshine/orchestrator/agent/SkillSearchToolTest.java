package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.SkillBodyRenderer;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S-C 候选动态加载元工具：候选集校验、正文+工具 schema 渲染、触发升级落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillSearchToolTest {

    private static final String MSG = "msg-sc";

    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private SkillBodyRenderer skillBodyRenderer;
    @Mock
    private ConversationService conversationService;
    @Mock
    private SandboxSessionLifecycle sandboxSessionLifecycle;

    private SkillSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new SkillSearchTool(skillCatalogService, skillBodyRenderer,
                conversationService, sandboxSessionLifecycle);
    }

    private static StepEventBridge.ToolAuditContext audit() {
        return new StepEventBridge.ToolAuditContext(
                "conv-1", MSG, "u1", "default", null, null, null, null, null, "chat");
    }

    private static ToolCallParam param(String skillId) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("skill_id", skillId);
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("call-1").name(SkillSearchTool.NAME).input(input).build())
                .input(input)
                .build();
    }

    private static SkillCatalogEntry skill(String id) {
        return new SkillCatalogEntry(id, "财务分析技能", "分析财务数据",
                "按四步法分析财务数据。", "[\"sdk__biz__list_expenses\"]",
                1, true, null, null, "all", null, "default");
    }

    private static String toolText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null || block.getOutput().isEmpty()) {
            return "";
        }
        Object first = block.getOutput().get(0);
        return first instanceof TextBlock t ? t.getText() : "";
    }

    @Test
    void loadCandidateSkill_returnsOverlayAndToolSchema_promotesTriggered() {
        when(skillCatalogService.find("finance-analysis")).thenReturn(Optional.of(skill("finance-analysis")));
        when(skillBodyRenderer.renderLoadedSkill(skill("finance-analysis"), "default", "chat"))
                .thenReturn("# 技能 finance-analysis\n\n按四步法分析财务数据。\n\n## 声明工具\n- **sdk__biz__list_expenses** 查询报销单：列出报销单");

        ToolResultBlock block = tool.execute(param("finance-analysis"), MSG, audit());

        assertThat(toolText(block))
                .contains("# 技能 finance-analysis")
                .contains("按四步法分析财务数据。")
                .contains("sdk__biz__list_expenses")
                .contains("列出报销单");
        verify(conversationService).appendTriggeredSkillId(MSG, "finance-analysis");
    }

    @Test
    void unknownSkill_returnsUnavailable() {
        when(skillCatalogService.find("unknown-skill")).thenReturn(Optional.empty());

        ToolResultBlock block = tool.execute(param("unknown-skill"), MSG, audit());

        assertThat(toolText(block)).contains("不存在或未启用");
        verify(conversationService, never()).appendTriggeredSkillId(anyString(), anyString());
    }

    @Test
    void disabledSkill_returnsUnavailable() {
        when(skillCatalogService.find("off-skill")).thenReturn(Optional.of(
                new SkillCatalogEntry("off-skill", "停用技能", "", "", "[]", 1, false,
                        null, null, "all", null, "default")));

        ToolResultBlock block = tool.execute(param("off-skill"), MSG, audit());

        assertThat(toolText(block)).contains("不存在或未启用");
        verify(conversationService, never()).appendTriggeredSkillId(anyString(), anyString());
    }

    @Test
    void tenantMismatch_returnsNotVisible() {
        when(skillCatalogService.find("other-tenant")).thenReturn(Optional.of(
                new SkillCatalogEntry("other-tenant", "他租户技能", "", "正文", "[]", 1, true,
                        null, null, "all", null, "other-tenant")));

        ToolResultBlock block = tool.execute(param("other-tenant"), MSG, audit());

        assertThat(toolText(block)).contains("对当前租户不可见");
        verify(conversationService, never()).appendTriggeredSkillId(anyString(), anyString());
    }

    @Test
    void blankSkillId_returnsError() {
        ToolResultBlock block = tool.execute(param(" "), MSG, audit());

        assertThat(toolText(block)).contains("skill_id 不能为空");
    }

    @Test
    void nullAudit_returnsContextError() {
        ToolResultBlock block = tool.execute(param("finance-analysis"), MSG, null);

        assertThat(toolText(block)).contains("缺少会话上下文");
    }

    @Test
    void declaredToolOutsideCurrentSet_markedNotCallable() {
        when(skillCatalogService.find("finance-analysis")).thenReturn(Optional.of(skill("finance-analysis")));
        when(skillBodyRenderer.renderLoadedSkill(skill("finance-analysis"), "default", "chat"))
                .thenReturn("# 技能 finance-analysis\n\n正文\n\n## 声明工具\n- sdk__biz__list_expenses：不在当前会话工具集内，不可调用");

        ToolResultBlock block = tool.execute(param("finance-analysis"), MSG, audit());

        assertThat(toolText(block)).contains("sdk__biz__list_expenses：不在当前会话工具集内，不可调用");
        verify(conversationService).appendTriggeredSkillId(MSG, "finance-analysis");
    }

    @Test
    void persistFailureDoesNotBlockBodyReturn() {
        when(skillCatalogService.find("finance-analysis")).thenReturn(Optional.of(skill("finance-analysis")));
        when(skillBodyRenderer.renderLoadedSkill(skill("finance-analysis"), "default", "chat"))
                .thenReturn("# 技能 finance-analysis\n\n正文");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(conversationService).appendTriggeredSkillId(anyString(), anyString());

        ToolResultBlock block = tool.execute(param("finance-analysis"), MSG, audit());

        assertThat(toolText(block)).contains("# 技能 finance-analysis");
    }
}
