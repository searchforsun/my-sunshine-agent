package com.sunshine.tool.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.tool.entity.ExecutionModePolicyEntity;
import com.sunshine.tool.repo.ExecutionModePolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({ExecutionModePolicyAdminService.class, ObjectMapper.class})
@ActiveProfiles("test")
class ExecutionModePolicyAdminServiceTest {

    @Autowired
    private ExecutionModePolicyAdminService policyAdminService;

    @Autowired
    private ExecutionModePolicyRepository policyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedGlobalPolicy() throws Exception {
        ExecutionModePolicyEntity global = new ExecutionModePolicyEntity();
        global.setId("global-plan-workflow-policy");
        global.setModeKey("plan_workflow");
        global.setTenantId(null);
        global.setPolicyJson(objectMapper.convertValue(PlanWorkflowExecutionPolicy.platformDefault(), java.util.Map.class));
        global.setUpdatedAt(java.time.Instant.now());
        policyRepository.save(global);
    }

    @Test
    void getPlanWorkflow_returnsGlobalWhenNoTenantOverride() {
        PlanWorkflowExecutionPolicy policy = policyAdminService.getPlanWorkflow(null);
        assertThat(policy.criticalOnFailure()).isEqualTo("fail_fast");
        assertThat(policy.byType().get("tool").maxAttempts()).isEqualTo(2);
    }

    @Test
    void putPlanWorkflow_createsTenantOverride() {
        PlanWorkflowExecutionPolicy custom = new PlanWorkflowExecutionPolicy(
                new PlanWorkflowExecutionPolicy.NodeDefaults(3, 500L, 2.0, "skip",
                        java.util.List.of("TIMEOUT")),
                java.util.Map.of("tool", new PlanWorkflowExecutionPolicy.NodeTypeOverride(1, "fail_fast")),
                "continue");
        policyAdminService.putPlanWorkflow("tenant-a", custom);
        assertThat(policyAdminService.getPlanWorkflow("tenant-a").criticalOnFailure()).isEqualTo("continue");
        assertThat(policyAdminService.getPlanWorkflow(null).criticalOnFailure()).isEqualTo("fail_fast");
    }
}
