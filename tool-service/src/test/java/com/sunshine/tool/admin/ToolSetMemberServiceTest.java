package com.sunshine.tool.admin;

import com.sunshine.common.tool.admin.ToolSetMemberAddItem;
import com.sunshine.common.tool.admin.ToolSetMemberAddRequest;
import com.sunshine.common.tool.admin.ToolSetMemberRemoveRequest;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.entity.ToolSetEntity;
import com.sunshine.tool.entity.ToolSetMemberEntity;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import com.sunshine.tool.repo.ToolSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(ToolSetMemberService.class)
@ActiveProfiles("test")
class ToolSetMemberServiceTest {

    @Autowired
    private ToolSetMemberService toolSetMemberService;

    @Autowired
    private ToolSetRepository toolSetRepository;

    @Autowired
    private ToolSetMemberRepository toolSetMemberRepository;

    @Autowired
    private ToolDefinitionRepository toolDefinitionRepository;

    @BeforeEach
    void seed() {
        ToolSetEntity chat = new ToolSetEntity();
        chat.setId("global-chat-default");
        chat.setSetType("global_chat_default");
        chat.setDisplayName("Chat");
        toolSetRepository.save(chat);

        ToolSetEntity task = new ToolSetEntity();
        task.setId("global-task-default");
        task.setSetType("global_task_default");
        task.setDisplayName("Task");
        toolSetRepository.save(task);

        saveTool("sdk__sunshine-finance__list_my_expenses", true);
        saveTool("sdk__sunshine-finance__get_expense_detail", true);
        saveTool("sdk__sunshine-oa__list_oa_tasks", false);
    }

    @Test
    void pageMembers_emptyByDefault() {
        var page = toolSetMemberService.pageMembers(ToolSetKind.CHAT_DEFAULT, null, 1, 20, null);
        assertThat(page.total()).isZero();
    }

    @Test
    void pageMembers_tenantWithoutSet_returnsEmpty() {
        var page = toolSetMemberService.pageMembers(ToolSetKind.CHAT_DEFAULT, "tenant-b", 1, 20, null);
        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void addMembers_addsEnabledAndRejectsDisabled() {
        var result = toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"),
                        new ToolSetMemberAddItem("sdk__sunshine-oa__list_oa_tasks"))));
        assertThat(result.added()).containsExactly("sdk__sunshine-finance__list_my_expenses");
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().getFirst().reason()).isEqualTo("not_enabled");
        assertThat(toolSetMemberService.pageMembers(ToolSetKind.CHAT_DEFAULT, null, 1, 20, null).total()).isOne();
    }

    @Test
    void addMembers_skipsDuplicate() {
        toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"))));
        var second = toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"))));
        assertThat(second.skipped()).containsExactly("sdk__sunshine-finance__list_my_expenses");
    }

    @Test
    void removeMembers_deletesFromSet() {
        toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"),
                        new ToolSetMemberAddItem("sdk__sunshine-finance__get_expense_detail"))));
        toolSetMemberService.removeMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberRemoveRequest(List.of("sdk__sunshine-finance__list_my_expenses")));
        assertThat(toolSetMemberService.pageMembers(ToolSetKind.CHAT_DEFAULT, null, 1, 20, null).total()).isOne();
    }

    @Test
    void toolIds_returnsMemberIds() {
        toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"))));
        assertThat(toolSetMemberService.toolIds(ToolSetKind.CHAT_DEFAULT, null).toolIds())
                .containsExactly("sdk__sunshine-finance__list_my_expenses");
    }

    @Test
    void toolIds_allIsUnionOfChatAndTask() {
        toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"))));
        toolSetMemberService.addMembers(
                ToolSetKind.TASK_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"),
                        new ToolSetMemberAddItem("sdk__sunshine-finance__get_expense_detail"))));
        assertThat(toolSetMemberService.toolIds(ToolSetKind.ALL_DEFAULT, null).toolIds())
                .containsExactly(
                        "sdk__sunshine-finance__list_my_expenses",
                        "sdk__sunshine-finance__get_expense_detail");
    }

    @Test
    void allKind_isReadOnlyUnionView() {
        assertThat(toolSetMemberService.toolIds(null, null).toolIds()).isEmpty();
        assertThatThrownBy(() -> toolSetMemberService.pageMembers(ToolSetKind.ALL_DEFAULT, null, 1, 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toolSetMemberService.picker(ToolSetKind.ALL_DEFAULT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toolSetMemberService.addMembers(
                ToolSetKind.ALL_DEFAULT, null, new ToolSetMemberAddRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toolSetMemberService.removeMembers(
                ToolSetKind.ALL_DEFAULT, null, new ToolSetMemberRemoveRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addMembers_writesOnlyNewSet() {
        toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"))));
        assertThat(toolSetMemberRepository.findBySetIdOrderBySortOrderAsc("global-chat-default")).hasSize(1);
    }

    @Test
    void pageMembers_returnsMemberBasics() {
        toolSetMemberService.addMembers(
                ToolSetKind.CHAT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_my_expenses"))));
        var item = toolSetMemberService.pageMembers(ToolSetKind.CHAT_DEFAULT, null, 1, 20, null)
                .items().getFirst();
        assertThat(item.toolId()).isEqualTo("sdk__sunshine-finance__list_my_expenses");
        assertThat(item.sourceLabel()).contains("SDK");
    }

    private void saveTool(String id, boolean enabled) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(id);
        entity.setSource("sdk");
        entity.setSourceRef("sunshine-finance");
        entity.setExternalName(id);
        entity.setDisplayName("tool");
        entity.setSchemaJson(Map.of("type", "object", "properties", Map.of()));
        entity.setSchemaHash("h");
        entity.setKind("remote");
        entity.setTimelineSummaryTemplate("{count} 条财务消息");
        entity.setTimelineSummaryExtract("{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}");
        entity.setTenantId("default");
        entity.setEnabled(enabled);
        toolDefinitionRepository.save(entity);
    }
}
