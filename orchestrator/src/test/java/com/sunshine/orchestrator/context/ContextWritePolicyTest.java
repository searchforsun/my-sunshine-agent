package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O3 写路由策略单点：矩阵（kind×mode → scope/scene/开关）+ 门禁 + TTL 表。
 * 路由结果必须与收敛前 ContextWritePath 现状一致（task-scene §2.2 / task-list-memory M1）。
 */
class ContextWritePolicyTest {

    private final ContextProperties properties = new ContextProperties();
    private final ContextWritePolicy policy = new ContextWritePolicy(properties);

    private static ChatConversationEntity conv(String kind, String mode, String workspaceId) {
        ChatConversationEntity c = new ChatConversationEntity();
        c.setId("c1");
        c.setKind(kind);
        c.setExecutionPreference(mode);
        c.setWorkspaceId(workspaceId);
        return c;
    }

    @Test
    void route_taskWorkflow_exitsUnifiedContextChain() {
        ContextWritePolicy.WriteDecision d = policy.route(conv("task", "workflow", "ws-1"));
        assertThat(d.writeL2()).isFalse();
        assertThat(d.writeL3()).isFalse();
        assertThat(d.scene()).isEqualTo("task");
        assertThat(d.scope()).isNull();
        assertThat(d.reason()).contains("退出统一上下文链路");
    }

    @Test
    void route_taskWorkflow_modeCaseInsensitive() {
        ContextWritePolicy.WriteDecision d = policy.route(conv("task", "Workflow", "ws-1"));
        assertThat(d.writeL2()).isFalse();
        assertThat(d.writeL3()).isFalse();
    }

    @Test
    void route_taskFast_writesWorkspaceScope() {
        ContextWritePolicy.WriteDecision d = policy.route(conv("task", "fast", "ws-1"));
        assertThat(d.writeL2()).isTrue();
        assertThat(d.writeL3()).isTrue();
        assertThat(d.scope()).isEqualTo("workspace");
        assertThat(d.workspaceId()).isEqualTo("ws-1");
        assertThat(d.scene()).isEqualTo("task");
    }

    @Test
    void route_taskPro_writesWorkspaceScope() {
        ContextWritePolicy.WriteDecision d = policy.route(conv("task", "pro", "ws-2"));
        assertThat(d.writeL2()).isTrue();
        assertThat(d.scope()).isEqualTo("workspace");
        assertThat(d.workspaceId()).isEqualTo("ws-2");
        assertThat(d.scene()).isEqualTo("task");
    }

    @Test
    void route_taskNullMode_defaultsWorkspace() {
        // executionPreference 为空的历史会话：仍属 task，写 workspace（与收敛前 "task".equals(kind) 分支一致）
        ContextWritePolicy.WriteDecision d = policy.route(conv("task", null, "ws-1"));
        assertThat(d.writeL2()).isTrue();
        assertThat(d.scope()).isEqualTo("workspace");
    }

    @Test
    void route_chat_writesUserScope() {
        ContextWritePolicy.WriteDecision d = policy.route(conv("chat", "fast", null));
        assertThat(d.writeL2()).isTrue();
        assertThat(d.writeL3()).isTrue();
        assertThat(d.scope()).isEqualTo("user");
        assertThat(d.workspaceId()).isNull();
        assertThat(d.scene()).isEqualTo("chat");
    }

    @Test
    void route_nullConversation_defaultsUserScope() {
        ContextWritePolicy.WriteDecision d = policy.route(null);
        assertThat(d.writeL2()).isTrue();
        assertThat(d.scope()).isEqualTo("user");
        assertThat(d.scene()).isEqualTo("chat");
    }

    @Test
    void l2TodoGatePasses_backgroundRequiredForActive() {
        assertThat(ContextWritePolicy.l2TodoGatePasses(
                "finance.follow_up", "跟进审批单", "报销收尾", "active")).isTrue();
        assertThat(ContextWritePolicy.l2TodoGatePasses(
                "finance.follow_up", "跟进审批单", "", "active")).isFalse();
    }

    @Test
    void l2TodoGatePasses_doneAndVoid_exemptBackground() {
        assertThat(ContextWritePolicy.l2TodoGatePasses(
                "finance.follow_up", "已完成", "", "done")).isTrue();
        assertThat(ContextWritePolicy.l2TodoGatePasses(
                "finance.follow_up", "作废", "", "void")).isTrue();
    }

    @Test
    void l2TodoGatePasses_rejectsBareKeyAndBooleanLoneValue() {
        assertThat(ContextWritePolicy.l2TodoGatePasses(
                "collect_receipts", "收齐发票", "报销", "active")).isFalse();
        assertThat(ContextWritePolicy.l2TodoGatePasses(
                "finance.follow_up", "true", "审批", "active")).isFalse();
    }

    @Test
    void l2IsBooleanLoneValue_caseInsensitive() {
        assertThat(ContextWritePolicy.l2IsBooleanLoneValue("TRUE")).isTrue();
        assertThat(ContextWritePolicy.l2IsBooleanLoneValue(" yes ")).isTrue();
        assertThat(ContextWritePolicy.l2IsBooleanLoneValue("确认")).isFalse();
    }

    @Test
    void l3TtlDays_chatScene_sharedTier() {
        ContextProperties.Maintenance m = new ContextProperties.Maintenance();
        assertThat(ContextWritePolicy.l3TtlDays("chat", null, m)).isEqualTo(m.getL3ChatTtlDays());
        assertThat(ContextWritePolicy.l3TtlDays("chat", "body", m)).isEqualTo(m.getL3ChatTtlDays());
        assertThat(ContextWritePolicy.l3TtlDays(null, "body", m)).isEqualTo(m.getL3ChatTtlDays());
    }

    @Test
    void l3TtlDays_taskScene_layeredTiers() {
        ContextProperties.Maintenance m = new ContextProperties.Maintenance();
        assertThat(ContextWritePolicy.l3TtlDays("task", "body", m)).isEqualTo(m.getL3TaskBodyTtlDays());
        assertThat(ContextWritePolicy.l3TtlDays("task", "process", m)).isEqualTo(m.getL3TaskProcessTtlDays());
        assertThat(ContextWritePolicy.l3TtlDays("task", "semantic", m)).isEqualTo(m.getL3TaskSemanticTtlDays());
        // 未知 layer 归 body 档
        assertThat(ContextWritePolicy.l3TtlDays("task", null, m)).isEqualTo(m.getL3TaskBodyTtlDays());
    }

    @Test
    void l2ExpiresAtFor_appliesTtlFromProperties() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        assertThat(policy.l2ExpiresAtFor("constraint", now))
                .isEqualTo(now.plus(properties.getL2().getConstraintTtlDays(), ChronoUnit.DAYS));
        assertThat(policy.l2ExpiresAtFor("todo", now))
                .isEqualTo(now.plus(properties.getL2().getTodoTtlDays(), ChronoUnit.DAYS));
    }

    @Test
    void l2ExpiresAtFor_zeroTtl_neverExpires() {
        properties.getL2().setFactTtlDays(0);
        assertThat(policy.l2ExpiresAtFor("fact", Instant.now())).isNull();
    }
}
