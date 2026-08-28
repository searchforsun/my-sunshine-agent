package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 业务上下文权威层装配（authority §4/§5）：
 * 闸门（开关/scene/kind）、Policy 解析（租户精确 > 平台默认；生效窗；最高 version）、
 * 任务板召回阶梯（会话锚定 / 最近一条详情 / 目录）、偏好白名单（scope + confirmed + TTL）。
 */
@ExtendWith(MockitoExtension.class)
class BusinessContextAssemblerTest {

    @Mock
    private BizSceneCatalogClient bizSceneCatalogClient;
    @Mock
    private BusinessTaskRepository businessTaskRepository;
    @Mock
    private UserContextStateRepository userContextStateRepository;

    private BusinessContextProperties properties;
    private BusinessContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new BusinessContextProperties();
        properties.setEnabled(true);
        properties.getPreference().setGlobalKeys(List.of("locale.language", "locale.timezone"));
        properties.getPreference().setWhitelist(Map.of(
                "expense-assist", List.of("refund.notify_channel")));
        assembler = new BusinessContextAssembler(
                properties, bizSceneCatalogClient, businessTaskRepository, userContextStateRepository);
        lenient().when(bizSceneCatalogClient.activePolicies()).thenReturn(List.of());
        lenient().when(businessTaskRepository
                .findByTenantIdAndUserIdAndBizSceneAndStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                        any(), any(), any(), anyCollection(), any()))
                .thenReturn(List.of());
    }

    @Test
    void assemble_disabledOrGateFails_returnsEmpty() {
        assertThat(assembler.assemble("default", "u1", "expense-assist", "c1", "task")).isEmpty();
        properties.setEnabled(false);
        assertThat(assembler.assemble("default", "u1", "expense-assist", "c1", "chat")).isEmpty();
        properties.setEnabled(true);
        assertThat(assembler.assemble("default", "u1", null, "c1", "chat")).isEmpty();
        assertThat(assembler.assemble("default", " ", "expense-assist", "c1", "chat")).isEmpty();
    }

    @Test
    void renderPolicyBlock_tenantExactBeatsPlatformDefault() {
        Instant now = Instant.now();
        when(bizSceneCatalogClient.activePolicies()).thenReturn(List.of(
                policy("*", "expense-assist", 2, "平台默认规则"),
                policy("default", "expense-assist", 1, "租户精确规则")));
        String block = assembler.renderPolicyBlock("default", "expense-assist", now);
        assertThat(block).contains("租户精确规则");
        assertThat(block).contains("expense-assist");
    }

    @Test
    void renderPolicyBlock_noTenantExactFallsBackToPlatform() {
        Instant now = Instant.now();
        when(bizSceneCatalogClient.activePolicies()).thenReturn(List.of(
                policy("*", "expense-assist", 1, "平台默认规则")));
        assertThat(assembler.renderPolicyBlock("tenant-x", "expense-assist", now))
                .contains("平台默认规则");
    }

    @Test
    void renderPolicyBlock_picksHighestVersionWithinWindow() {
        Instant now = Instant.now();
        when(bizSceneCatalogClient.activePolicies()).thenReturn(List.of(
                policy("default", "expense-assist", 1, "旧版规则",
                        now.minus(10, ChronoUnit.DAYS), null),
                policy("default", "expense-assist", 3, "最新版规则",
                        now.minus(1, ChronoUnit.DAYS), null),
                // 生效窗未开（effective_from 在未来）→ 即使 version 更高也不装载
                policy("default", "expense-assist", 9, "未来生效规则",
                        now.plus(1, ChronoUnit.DAYS), null)));
        assertThat(assembler.renderPolicyBlock("default", "expense-assist", now))
                .contains("最新版规则")
                .doesNotContain("未来生效规则");
    }

    @Test
    void renderPolicyBlock_sceneMismatchOrExpiredWindowEmpty() {
        Instant now = Instant.now();
        when(bizSceneCatalogClient.activePolicies()).thenReturn(List.of(
                policy("default", "travel-budget", 1, "差旅规则"),
                policy("default", "expense-assist", 1, "已过期规则",
                        null, now.minus(1, ChronoUnit.DAYS))));
        assertThat(assembler.renderPolicyBlock("default", "expense-assist", now)).isEmpty();
    }

    @Test
    void renderTaskBlock_conversationAnchorBeatsRecency() {
        Instant now = Instant.now();
        BusinessTaskEntity newest = task("t1", "最新任务", "running", now);
        BusinessTaskEntity anchored = task("t2", "本会话任务", "awaiting_confirm", now);
        anchored.setConversationId("c1");
        anchored.setExternalTicketRef("OA-1024");
        anchored.setRiskLevel("high");
        when(businessTaskRepository
                .findByTenantIdAndUserIdAndBizSceneAndStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                        eq("default"), eq("u1"), eq("expense-assist"), anyCollection(), any()))
                .thenReturn(List.of(newest, anchored));
        String block = assembler.renderTaskBlock("default", "u1", "expense-assist", "c1", now);
        assertThat(block).contains("本会话任务");
        assertThat(block).contains("OA-1024");
        assertThat(block).contains("high");
        assertThat(block).doesNotContain("当前焦点任务：最新任务");
    }

    @Test
    void renderTaskBlock_noAnchorUsesMostRecentPlusDirectory() {
        Instant now = Instant.now();
        BusinessTaskEntity first = task("t1", "最近任务", "running", now);
        first.setStepsJson("[\"提交报销单\",\"等待审批\"]");
        BusinessTaskEntity second = task("t2", "次新任务", "pending", now);
        when(businessTaskRepository
                .findByTenantIdAndUserIdAndBizSceneAndStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                        any(), any(), any(), anyCollection(), any()))
                .thenReturn(List.of(first, second));
        String block = assembler.renderTaskBlock("default", "u1", "expense-assist", null, now);
        assertThat(block).contains("当前焦点任务：最近任务");
        assertThat(block).contains("步骤骨架");
        // 目录含两条（topK=5 > 候选 2 条）
        assertThat(block).contains("同场景活跃任务目录");
        assertThat(block).contains("t1").contains("t2");
    }

    @Test
    void renderTaskBlock_noActiveTaskEmpty() {
        assertThat(assembler.renderTaskBlock("default", "u1", "expense-assist", null, Instant.now()))
                .isEmpty();
    }

    @Test
    void renderPreferenceBlock_whitelistAndScopeFilters() {
        Instant now = Instant.now();
        UserContextStateEntity scenePref = preference("refund.notify_channel", "邮件", "expense-assist");
        UserContextStateEntity globalPref = preference("locale.language", "zh-CN", "*");
        UserContextStateEntity offScenePref = preference("approve.notify_channel", "钉钉", "contract_approve");
        UserContextStateEntity inferredPref = preference("locale.language", "en-US", "*");
        inferredPref.setConfirmStatus("inferred");
        UserContextStateEntity expiredPref = preference("locale.timezone", "UTC+8", "*");
        expiredPref.setExpiresAt(now.minus(1, ChronoUnit.DAYS));
        when(userContextStateRepository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(scenePref, globalPref, offScenePref, inferredPref, expiredPref));
        String block = assembler.renderPreferenceBlock("default", "u1", "expense-assist", now);
        assertThat(block).contains("refund.notify_channel: 邮件");
        assertThat(block).contains("locale.language: zh-CN");
        assertThat(block).doesNotContain("approve.notify_channel");
        assertThat(block).doesNotContain("en-US");
        // locale.timezone 属全局白名单但已过期
        assertThat(block).doesNotContain("locale.timezone");
    }

    @Test
    void renderPreferenceBlock_noWhitelistEmpty() {
        properties.getPreference().setGlobalKeys(List.of());
        properties.getPreference().setWhitelist(Map.of());
        assertThat(assembler.renderPreferenceBlock("default", "u1", "expense-assist", Instant.now()))
                .isEmpty();
    }

    @Test
    void assemble_combinesBlocksInPriorityOrder() {
        Instant now = Instant.now();
        when(bizSceneCatalogClient.activePolicies()).thenReturn(List.of(
                policy("default", "expense-assist", 1, "报销红线：单笔超 5000 必须审批")));
        when(businessTaskRepository
                .findByTenantIdAndUserIdAndBizSceneAndStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                        any(), any(), any(), anyCollection(), any()))
                .thenReturn(List.of(task("t1", "进行中报销", "running", now)));
        when(userContextStateRepository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(preference("refund.notify_channel", "邮件", "expense-assist")));
        List<String> blocks = assembler.assemble("default", "u1", "expense-assist", "c1", "chat");
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).startsWith("【场景Policy");
        assertThat(blocks.get(1)).startsWith("【业务任务板");
        assertThat(blocks.get(2)).startsWith("【场景偏好");
    }

    private static BizSceneCatalogClient.CachedPolicy policy(
            String tenantId, String scene, int version, String rules) {
        return policy(tenantId, scene, version, rules, null, null);
    }

    private static BizSceneCatalogClient.CachedPolicy policy(
            String tenantId, String scene, int version, String rules,
            Instant effectiveFrom, Instant effectiveTo) {
        return new BizSceneCatalogClient.CachedPolicy(
                tenantId, scene, version, rules, effectiveFrom, effectiveTo);
    }

    private static BusinessTaskEntity task(String taskId, String title, String status, Instant updatedAt) {
        BusinessTaskEntity entity = new BusinessTaskEntity();
        entity.setTaskId(taskId);
        entity.setTenantId("default");
        entity.setUserId("u1");
        entity.setBizScene("expense-assist");
        entity.setStatus(status);
        entity.setTitle(title);
        entity.setRiskLevel("low");
        entity.setCreatedAt(updatedAt);
        entity.setUpdatedAt(updatedAt);
        return entity;
    }

    private static UserContextStateEntity preference(String key, String value, String bizSceneScope) {
        UserContextStateEntity entity = new UserContextStateEntity();
        entity.setId("p-" + key);
        entity.setScope("user");
        entity.setUserId("u1");
        entity.setTenantId("default");
        entity.setKind("preference");
        entity.setStateKey(key);
        entity.setStateValue(value);
        entity.setBizSceneScope(bizSceneScope);
        entity.setConfirmStatus("confirmed");
        entity.setConfidence(0.9);
        entity.setStatus("active");
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
