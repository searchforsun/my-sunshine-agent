package com.sunshine.tool.admin;

import com.sunshine.tool.admin.dto.ToolSetUpdateRequest;
import com.sunshine.tool.entity.ToolSetEntity;
import com.sunshine.tool.entity.ToolSetMemberEntity;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import com.sunshine.tool.repo.ToolSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(ToolSetAdminService.class)
@ActiveProfiles("test")
class ToolSetAdminServiceTest {

    @Autowired
    private ToolSetAdminService toolSetAdminService;

    @Autowired
    private ToolSetRepository toolSetRepository;

    @Autowired
    private ToolSetMemberRepository toolSetMemberRepository;

    @BeforeEach
    void seedGlobalSet() {
        ToolSetEntity react = new ToolSetEntity();
        react.setId("global-react-default");
        react.setSetType("global_react_default");
        react.setTenantId(null);
        react.setDisplayName("平台 ReAct 默认工具集");
        toolSetRepository.save(react);

        ToolSetMemberEntity reactMember = new ToolSetMemberEntity();
        reactMember.setSetId("global-react-default");
        reactMember.setToolId("sdk__sunshine-finance__list_finance_messages");
        reactMember.setSortOrder(0);
        toolSetMemberRepository.save(reactMember);

        ToolSetEntity planCritical = new ToolSetEntity();
        planCritical.setId("global-plan-workflow-critical");
        planCritical.setSetType("global_plan_workflow_critical");
        planCritical.setTenantId(null);
        planCritical.setDisplayName("平台 Plan/Workflow 关键工具集");
        toolSetRepository.save(planCritical);

        ToolSetMemberEntity planMember = new ToolSetMemberEntity();
        planMember.setSetId("global-plan-workflow-critical");
        planMember.setToolId("sdk__sunshine-finance__get_finance_message_detail");
        planMember.setSortOrder(0);
        toolSetMemberRepository.save(planMember);
    }

    @Test
    void getReactDefault_returnsGlobalWhenNoTenantOverride() {
        assertThat(toolSetAdminService.getReactDefault(null).toolIds())
                .containsExactly("sdk__sunshine-finance__list_finance_messages");
        assertThat(toolSetAdminService.getReactDefault("default").toolIds())
                .containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void putReactDefault_createsTenantOverride() {
        toolSetAdminService.putReactDefault("tenant-a", new ToolSetUpdateRequest(
                java.util.List.of("sdk__sunshine-oa__list_oa_tasks", "sdk__sunshine-oa__approve_oa_task")));
        assertThat(toolSetAdminService.getReactDefault("tenant-a").toolIds())
                .containsExactly("sdk__sunshine-oa__list_oa_tasks", "sdk__sunshine-oa__approve_oa_task");
        assertThat(toolSetAdminService.getReactDefault(null).toolIds())
                .containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void putReactDefault_updatesGlobalSet() {
        toolSetAdminService.putReactDefault(null, new ToolSetUpdateRequest(
                java.util.List.of("sdk__sunshine-oa__list_oa_tasks")));
        assertThat(toolSetAdminService.getReactDefault(null).toolIds())
                .containsExactly("sdk__sunshine-oa__list_oa_tasks");
    }

    @Test
    void getPlanWorkflowCritical_returnsGlobalWhenNoTenantOverride() {
        assertThat(toolSetAdminService.getPlanWorkflowCritical(null).toolIds())
                .containsExactly("sdk__sunshine-finance__get_finance_message_detail");
    }

    @Test
    void putPlanWorkflowCritical_createsTenantOverride() {
        toolSetAdminService.putPlanWorkflowCritical("tenant-a", new ToolSetUpdateRequest(
                java.util.List.of("sdk__sunshine-oa__list_oa_tasks")));
        assertThat(toolSetAdminService.getPlanWorkflowCritical("tenant-a").toolIds())
                .containsExactly("sdk__sunshine-oa__list_oa_tasks");
        assertThat(toolSetAdminService.getPlanWorkflowCritical(null).toolIds())
                .containsExactly("sdk__sunshine-finance__get_finance_message_detail");
    }
}
