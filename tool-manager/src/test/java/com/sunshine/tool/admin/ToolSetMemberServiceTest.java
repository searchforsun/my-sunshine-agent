package com.sunshine.tool.admin;

import com.sunshine.tool.admin.dto.ToolSetMemberAddItem;
import com.sunshine.tool.admin.dto.ToolSetMemberAddRequest;
import com.sunshine.tool.admin.dto.ToolSetMemberRemoveRequest;
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
        ToolSetEntity react = new ToolSetEntity();
        react.setId("global-react-default");
        react.setSetType("global_react_default");
        react.setDisplayName("ReAct");
        toolSetRepository.save(react);

        ToolSetEntity plan = new ToolSetEntity();
        plan.setId("global-plan-workflow");
        plan.setSetType("global_plan_workflow");
        plan.setDisplayName("Plan");
        toolSetRepository.save(plan);

        saveTool("sdk__sunshine-finance__list_finance_messages", true);
        saveTool("sdk__sunshine-finance__get_finance_message_detail", true);
        saveTool("sdk__sunshine-oa__list_oa_tasks", false);
    }

    @Test
    void pageMembers_emptyByDefault() {
        var page = toolSetMemberService.pageMembers(ToolSetKind.REACT_DEFAULT, null, 1, 20, null);
        assertThat(page.total()).isZero();
    }

    @Test
    void pageMembers_tenantWithoutSet_returnsEmpty() {
        var page = toolSetMemberService.pageMembers(ToolSetKind.REACT_DEFAULT, "tenant-b", 1, 20, null);
        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void addMembers_addsEnabledAndRejectsDisabled() {
        var result = toolSetMemberService.addMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", false),
                        new ToolSetMemberAddItem("sdk__sunshine-oa__list_oa_tasks", false))));
        assertThat(result.added()).containsExactly("sdk__sunshine-finance__list_finance_messages");
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().getFirst().reason()).isEqualTo("not_enabled");
        assertThat(toolSetMemberService.pageMembers(ToolSetKind.REACT_DEFAULT, null, 1, 20, null).total()).isOne();
    }

    @Test
    void addMembers_skipsDuplicate() {
        toolSetMemberService.addMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", false))));
        var second = toolSetMemberService.addMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", false))));
        assertThat(second.skipped()).containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void removeMembers_deletesFromSet() {
        toolSetMemberService.addMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", false),
                        new ToolSetMemberAddItem("sdk__sunshine-finance__get_finance_message_detail", false))));
        toolSetMemberService.removeMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberRemoveRequest(List.of("sdk__sunshine-finance__list_finance_messages")));
        assertThat(toolSetMemberService.pageMembers(ToolSetKind.REACT_DEFAULT, null, 1, 20, null).total()).isOne();
    }

    @Test
    void patchCritical_marksPlanMember() {
        toolSetMemberService.addMembers(
                ToolSetKind.PLAN_WORKFLOW,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", true))));
        var ids = toolSetMemberService.toolIds(ToolSetKind.PLAN_WORKFLOW, null);
        assertThat(ids.criticalToolIds()).containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void toolIds_returnsMemberIds() {
        toolSetMemberService.addMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", false))));
        assertThat(toolSetMemberService.toolIds(ToolSetKind.REACT_DEFAULT, null).toolIds())
                .containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void pageMembers_returnsMemberBasics() {
        toolSetMemberService.addMembers(
                ToolSetKind.REACT_DEFAULT,
                null,
                new ToolSetMemberAddRequest(List.of(
                        new ToolSetMemberAddItem("sdk__sunshine-finance__list_finance_messages", false))));
        var item = toolSetMemberService.pageMembers(ToolSetKind.REACT_DEFAULT, null, 1, 20, null)
                .items().getFirst();
        assertThat(item.toolId()).isEqualTo("sdk__sunshine-finance__list_finance_messages");
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
