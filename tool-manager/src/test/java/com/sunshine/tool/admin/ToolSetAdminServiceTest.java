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
        reactMember.setCritical(false);
        toolSetMemberRepository.save(reactMember);

        ToolSetEntity plan = new ToolSetEntity();
        plan.setId("global-plan-workflow");
        plan.setSetType("global_plan_workflow");
        plan.setTenantId(null);
        plan.setDisplayName("平台 Plan-Workflow 工具集");
        toolSetRepository.save(plan);
    }

    @Test
    void getPlanWorkflow_returnsGlobalWhenNoTenantOverride() {
        ToolSetMemberEntity planMember = new ToolSetMemberEntity();
        planMember.setSetId("global-plan-workflow");
        planMember.setToolId("sdk__sunshine-finance__summarize_finance_by_status");
        planMember.setSortOrder(0);
        planMember.setCritical(false);
        toolSetMemberRepository.save(planMember);

        assertThat(toolSetAdminService.getPlanWorkflow(null).toolIds())
                .containsExactly("sdk__sunshine-finance__summarize_finance_by_status");
    }

    @Test
    void putPlanWorkflow_createsTenantOverride() {
        toolSetAdminService.putPlanWorkflow("tenant-a", new ToolSetUpdateRequest(
                java.util.List.of("sdk__sunshine-oa__list_oa_tasks")));
        assertThat(toolSetAdminService.getPlanWorkflow("tenant-a").toolIds())
                .containsExactly("sdk__sunshine-oa__list_oa_tasks");
    }

    @Test
    void getReactDefault_returnsGlobalForDefaultTenant() {
        assertThat(toolSetAdminService.getReactDefault(null).toolIds())
                .containsExactly("sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void getReactDefault_tenantWithoutSet_returnsEmpty() {
        assertThat(toolSetAdminService.getReactDefault("tenant-a").toolIds()).isEmpty();
    }

    @Test
    void putReactDefault_createsTenantSet() {
        toolSetAdminService.putReactDefault("tenant-a", new ToolSetUpdateRequest(
                java.util.List.of("sdk__sunshine-oa__list_oa_tasks", "sdk__sunshine-oa__approve_oa_task")));
        assertThat(toolSetAdminService.getReactDefault("tenant-a").toolIds())
                .containsExactly("sdk__sunshine-oa__list_oa_tasks", "sdk__sunshine-oa__approve_oa_task");
    }
}
