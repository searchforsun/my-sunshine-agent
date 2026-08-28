package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 业务上下文权威层装配（authority §5.3）：有 {@code biz_scene} 时装载
 * Policy ∥ 业务任务板 ∥ 场景偏好三块，按硬优先级序输出（policy &gt; business_task &gt; prefs）。
 * <p>启用面一期 = {@code kind=chat} 且 Nacos 开关开；无 scene 或闸门不满足返回空（整块跳过）。
 * 块为结构化权威渲染（SQL 精确匹配），禁止向量召回决定装载内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessContextAssembler {

    private static final List<String> ACTIVE_TASK_STATUS = List.of("pending", "running", "awaiting_confirm");

    private final BusinessContextProperties properties;
    private final BizSceneCatalogClient bizSceneCatalogClient;
    private final BusinessTaskRepository businessTaskRepository;
    private final UserContextStateRepository userContextStateRepository;

    /**
     * 装载请求：任一闸门不满足 → 空列表。
     * <ul>
     *   <li>开关关 / kind != chat（一期主路径；task 走 task-scene 跳过本层）</li>
     *   <li>{@code bizScene} 为空（资源召回未带出场景码 → 跳过结构化层）</li>
     * </ul>
     */
    public List<String> assemble(
            String tenantId, String userId, String bizScene, String conversationId, String kind) {
        if (!properties.isEnabled() || !"chat".equals(kind)) {
            log.info("[BizContext] skip gate={} enabled={}", kind, properties.isEnabled());
            return List.of();
        }
        if (!StringUtils.hasText(bizScene)) {
            log.info("[BizContext] skip scene=null（资源召回未带出场景码）");
            return List.of();
        }
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        String scene = bizScene.strip();
        String tid = StringUtils.hasText(tenantId) ? tenantId : "default";
        Instant now = Instant.now();
        List<String> blocks = new ArrayList<>();
        String policyBlock = renderPolicyBlock(tid, scene, now);
        if (StringUtils.hasText(policyBlock)) {
            blocks.add(policyBlock);
        }
        String taskBlock = renderTaskBlock(tid, userId.strip(), scene, conversationId, now);
        if (StringUtils.hasText(taskBlock)) {
            blocks.add(taskBlock);
        }
        String prefBlock = renderPreferenceBlock(tid, userId.strip(), scene, now);
        if (StringUtils.hasText(prefBlock)) {
            blocks.add(prefBlock);
        }
        log.info("[BizContext] loaded scene={} policy={} task={} prefs={} blocks={}",
                scene, StringUtils.hasText(policyBlock), StringUtils.hasText(taskBlock),
                StringUtils.hasText(prefBlock), blocks.size());
        return List.copyOf(blocks);
    }

    /**
     * Policy（§4.2）：tenant 精确优先，否则回落 {@code *} 平台默认；仅一条
     * 「当前 active 且在生效窗内的最高 version」。渲染规则提示词原文（运营维护于 Lab）。
     */
    String renderPolicyBlock(String tenantId, String scene, Instant now) {
        BizSceneCatalogClient.CachedPolicy picked = null;
        BizSceneCatalogClient.CachedPolicy fallback = null;
        for (BizSceneCatalogClient.CachedPolicy policy : bizSceneCatalogClient.activePolicies()) {
            if (!scene.equals(policy.bizScene()) || !inEffectiveWindow(policy, now)) {
                continue;
            }
            if (Objects.equals(policy.tenantId(), tenantId)) {
                picked = higherVersion(picked, policy);
            } else if ("*".equals(policy.tenantId())) {
                fallback = higherVersion(fallback, policy);
            }
        }
        BizSceneCatalogClient.CachedPolicy effective = picked != null ? picked : fallback;
        if (effective == null || !StringUtils.hasText(effective.rulesJson())) {
            return "";
        }
        return "【场景Policy · " + scene + "（v" + effective.version() + "）】\n" + effective.rulesJson().strip();
    }

    /**
     * 业务任务板（§4.1 召回阶梯）：候选池（同场景活跃 + 时间窗）→
     * 锚定（会话已绑定活跃任务 → 装该条详情；否则最近 1 条详情）→
     * 附极简目录（≤ top-k 行：id+title+status）。done/archived 不进 Prompt。
     */
    String renderTaskBlock(String tenantId, String userId, String scene, String conversationId, Instant now) {
        List<BusinessTaskEntity> candidates = businessTaskRepository
                .findByTenantIdAndUserIdAndBizSceneAndStatusInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                        tenantId, userId, scene, ACTIVE_TASK_STATUS,
                        now.minusSeconds(properties.getTask().getActiveDays() * 86_400L));
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        BusinessTaskEntity focus = null;
        if (StringUtils.hasText(conversationId)) {
            for (BusinessTaskEntity entity : candidates) {
                if (conversationId.equals(entity.getConversationId())) {
                    focus = entity;
                    break;
                }
            }
        }
        if (focus == null) {
            focus = candidates.get(0);
        }
        StringBuilder sb = new StringBuilder("【业务任务板 · ").append(scene).append("】\n");
        sb.append("当前焦点任务：").append(focus.getTitle())
                .append("（").append(focus.getStatus());
        if (StringUtils.hasText(focus.getExternalTicketRef())) {
            sb.append(" · 单号 ").append(focus.getExternalTicketRef());
        }
        if (StringUtils.hasText(focus.getRiskLevel()) && !"low".equals(focus.getRiskLevel())) {
            sb.append(" · 风险 ").append(focus.getRiskLevel());
        }
        sb.append('）');
        if (StringUtils.hasText(focus.getStepsJson())) {
            sb.append('\n').append("步骤骨架：").append(focus.getStepsJson());
        }
        if (StringUtils.hasText(focus.getPendingConfirmationsJson())) {
            sb.append('\n').append("待确认：").append(focus.getPendingConfirmationsJson());
        }
        int topK = Math.min(properties.getTask().getTopK(), candidates.size());
        if (topK > 1) {
            sb.append('\n').append("同场景活跃任务目录（").append(topK).append(" 条）：");
            for (int i = 0; i < topK; i++) {
                BusinessTaskEntity entity = candidates.get(i);
                sb.append('\n').append("- ").append(entity.getTaskId())
                        .append(' ').append(entity.getTitle())
                        .append("（").append(entity.getStatus()).append('）');
            }
        }
        return sb.toString();
    }

    /**
     * 场景偏好（§4.3）：仅 {@code confirm_status=confirmed} ∧ 未过期 ∧
     * key ∈ 白名单。{@code biz_scene_scope=*} 走全局白名单；
     * {@code biz_scene_scope=scene} 走该场景白名单；未配置白名单即不装载（反全量灌）。
     */
    String renderPreferenceBlock(String tenantId, String userId, String scene, Instant now) {
        List<String> sceneKeys = properties.getPreference().getWhitelist().get(scene);
        List<String> globalKeys = properties.getPreference().getGlobalKeys();
        if ((sceneKeys == null || sceneKeys.isEmpty()) && (globalKeys == null || globalKeys.isEmpty())) {
            return "";
        }
        List<UserContextStateEntity> entries = userContextStateRepository
                .findByUserIdAndTenantIdAndStatus(userId, tenantId, "active");
        List<String> lines = new ArrayList<>();
        if (entries != null) {
            for (UserContextStateEntity entity : entries) {
                if (!isScenePreference(entity)) {
                    continue;
                }
                if (!whitelistPasses(entity, scene, sceneKeys, globalKeys)) {
                    continue;
                }
                if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(now)) {
                    continue;
                }
                lines.add("- " + entity.getStateKey() + ": " + entity.getStateValue());
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "【场景偏好 · " + scene + "】\n" + String.join("\n", lines);
    }

    /** 偏好装载仅收 preference 类 + confirmed 态（inferred 默认不装载，§4.3）。 */
    private static boolean isScenePreference(UserContextStateEntity entity) {
        return entity != null
                && "preference".equals(entity.getKind())
                && "confirmed".equals(normalizeConfirmStatus(entity.getConfirmStatus()))
                && StringUtils.hasText(entity.getStateKey());
    }

    private static String normalizeConfirmStatus(String confirmStatus) {
        return StringUtils.hasText(confirmStatus) ? confirmStatus.strip() : "confirmed";
    }

    /** scope=* → key ∈ 全局白名单；scope=scene → key ∈ 该场景白名单；其余作用域不装载。 */
    private static boolean whitelistPasses(
            UserContextStateEntity entity,
            String scene,
            List<String> sceneKeys,
            List<String> globalKeys) {
        String scope = StringUtils.hasText(entity.getBizSceneScope())
                ? entity.getBizSceneScope().strip() : "*";
        if ("*".equals(scope)) {
            return globalKeys != null && globalKeys.contains(entity.getStateKey());
        }
        if (scope.equals(scene)) {
            return sceneKeys != null && sceneKeys.contains(entity.getStateKey());
        }
        return false;
    }

    private static BizSceneCatalogClient.CachedPolicy higherVersion(
            BizSceneCatalogClient.CachedPolicy current, BizSceneCatalogClient.CachedPolicy candidate) {
        if (current == null || candidate.version() > current.version()) {
            return candidate;
        }
        return current;
    }

    /** 生效窗判定：边界为 null 视为无限（运营未设窗口 = 长期生效）。 */
    private static boolean inEffectiveWindow(BizSceneCatalogClient.CachedPolicy policy, Instant now) {
        if (policy.effectiveFrom() != null && now.isBefore(policy.effectiveFrom())) {
            return false;
        }
        return policy.effectiveTo() == null || !now.isAfter(policy.effectiveTo());
    }
}
