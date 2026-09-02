package com.sunshine.bizscene.service;

import com.sunshine.bizscene.dto.BizSceneCreateRequest;
import com.sunshine.bizscene.dto.BizSceneDefinitionView;
import com.sunshine.bizscene.dto.BizSceneEmbeddingItem;
import com.sunshine.bizscene.dto.BizScenePolicySaveRequest;
import com.sunshine.bizscene.dto.BizScenePolicyView;
import com.sunshine.bizscene.dto.BizSceneUpdateRequest;
import com.sunshine.bizscene.dto.BizSceneVectorRequest;
import com.sunshine.bizscene.entity.BizSceneDefinitionEntity;
import com.sunshine.bizscene.entity.BizScenePolicyEntity;
import com.sunshine.bizscene.exception.BizSceneErrorCode;
import com.sunshine.bizscene.repo.BizSceneDefinitionRepository;
import com.sunshine.bizscene.repo.BizScenePolicyRepository;
import com.sunshine.common.core.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 业务场景 Lab：biz_scene 闭集码表 + 场景 Policy 管理（K2）。运行时禁止新建码；disabled 不可新绑。 */
@Service
@RequiredArgsConstructor
public class BizSceneAdminService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{2,48}");
    private static final Set<String> VALID_STATUS = Set.of(
            "active", "disabled", "pending_review", "rejected", "auto_cleaned");
    private static final Set<String> VALID_SOURCE = Set.of("manual", "auto");

    private final BizSceneDefinitionRepository definitionRepository;
    private final BizScenePolicyRepository policyRepository;

    public List<BizSceneDefinitionView> listScenes() {
        return definitionRepository.findAll().stream()
                .map(BizSceneAdminService::toView)
                .toList();
    }

    /** 可绑定/可解析的 active 码闭集（运行时 BizScene 解析 + UI 下拉共用） */
    public List<String> listActiveCodes() {
        return definitionRepository.findAll().stream()
                .filter(def -> "active".equals(def.getStatus()))
                .map(BizSceneDefinitionEntity::getBizScene)
                .sorted()
                .toList();
    }

    @Transactional
    public BizSceneDefinitionView createScene(BizSceneCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.bizScene())) {
            throw new BizException(BizSceneErrorCode.CODE_REQUIRED);
        }
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(BizSceneErrorCode.DISPLAY_NAME_REQUIRED);
        }
        String code = request.bizScene().strip();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BizException(BizSceneErrorCode.INVALID_CODE_FORMAT);
        }
        String source = StringUtils.hasText(request.source()) ? request.source().strip() : "manual";
        if (!VALID_SOURCE.contains(source)) {
            throw new BizException(BizSceneErrorCode.INVALID_SOURCE);
        }
        if (definitionRepository.existsById(code)) {
            throw new BizException(BizSceneErrorCode.SCENE_ALREADY_EXISTS);
        }
        BizSceneDefinitionEntity def = new BizSceneDefinitionEntity();
        def.setBizScene(code);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        // auto 场景初始 pending_review，仅嵌入检索可用；运营审核后升 active（authority §2.1c）
        if ("auto".equals(source)) {
            def.setStatus("pending_review");
            def.setSourceConversationId(request.sourceConversationId());
        }
        def.setSource(source);
        return toView(definitionRepository.save(def));
    }

    @Transactional
    public BizSceneDefinitionView updateScene(String bizScene, BizSceneUpdateRequest request) {
        BizSceneDefinitionEntity def = requireScene(bizScene);
        if (request == null) {
            return toView(def);
        }
        if (StringUtils.hasText(request.displayName())) {
            def.setDisplayName(request.displayName().strip());
        }
        if (request.description() != null) {
            def.setDescription(request.description().strip());
        }
        if (StringUtils.hasText(request.status())) {
            String status = request.status().strip();
            if (!VALID_STATUS.contains(status)) {
                throw new BizException(BizSceneErrorCode.INVALID_STATUS);
            }
            def.setStatus(status);
            // 审核通过（auto 场景升 active）：记录审核人与时间
            if ("active".equals(status) && StringUtils.hasText(request.approvedBy())) {
                def.setApprovedBy(request.approvedBy().strip());
                def.setApprovedAt(Instant.now());
            }
        }
        def.setUpdatedAt(Instant.now());
        return toView(definitionRepository.save(def));
    }

    /** 场景 description 向量回填（orchestrator 场景 embedding 服务计算后推送，authority §4.4）。 */
    @Transactional
    public void updateVector(String bizScene, BizSceneVectorRequest request) {
        BizSceneDefinitionEntity def = requireScene(bizScene);
        if (request == null || request.vector() == null) {
            throw new BizException(BizSceneErrorCode.INVALID_STATUS);
        }
        def.setDescriptionVector(jsonVector(request.vector()));
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
    }

    /** 场景 embedding 检索索引（authority §2.1b）：全量非软删场景，含向量与来源/状态，供 orchestrator 缓存匹配。 */
    public List<BizSceneEmbeddingItem> listEmbeddingIndex() {
        return definitionRepository.findAll().stream()
                .filter(def -> !"auto_cleaned".equals(def.getStatus()))
                .map(def -> new BizSceneEmbeddingItem(
                        def.getBizScene(), def.getDisplayName(), def.getDescription(),
                        def.getDescriptionVector(), def.getStatus(), def.getSource(), def.getTenantId()))
                .toList();
    }

    /** 删除场景：级联删除其全部 Policy；已被 Skill/Agent 引用时解析视为无效（无 FK，由运行时跳过） */
    @Transactional
    public void deleteScene(String bizScene) {
        BizSceneDefinitionEntity def = requireScene(bizScene);
        policyRepository.deleteAll(policyRepository.findByBizSceneOrderByVersionDesc(bizScene));
        definitionRepository.delete(def);
    }

    /** 规则列表：按场景、序号升序（每条规则独立一项，不做版本语义） */
    public List<BizScenePolicyView> listPolicies(String tenantId) {
        return policyRepository.findByTenantIdOrderByBizSceneAscVersionAsc(
                        StringUtils.hasText(tenantId) ? tenantId : "default").stream()
                .map(BizSceneAdminService::toView)
                .toList();
    }

    /**
     * 运行时装载视图：全租户（含 {@code *} 平台默认）的 active Policy 快照。
     * 供 orchestrator 低频缓存装载；生效窗/最高 version 解析在消费侧（authority §4.2）。
     */
    public List<BizScenePolicyView> listActivePolicies() {
        return policyRepository.findByStatusOrderByBizSceneAscVersionAsc("active").stream()
                .map(BizSceneAdminService::toView)
                .toList();
    }

    /** 新增规则：码必须存在且 active（禁止无 Lab 码创建）；序号在既有基础上单调递增 */
    @Transactional
    public BizScenePolicyView createPolicy(String tenantId, BizScenePolicySaveRequest request) {
        if (request == null || !StringUtils.hasText(request.bizScene())) {
            throw new BizException(BizSceneErrorCode.CODE_REQUIRED);
        }
        String scene = request.bizScene().strip();
        BizSceneDefinitionEntity def = requireScene(scene);
        if (!"active".equals(def.getStatus())) {
            throw new BizException(BizSceneErrorCode.SCENE_NOT_ACTIVE);
        }
        List<BizScenePolicyEntity> existing =
                policyRepository.findByBizSceneOrderByVersionDesc(scene);
        int nextSeq = existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1;

        BizScenePolicyEntity policy = new BizScenePolicyEntity();
        policy.setTenantId(StringUtils.hasText(tenantId) ? tenantId : "default");
        policy.setBizScene(scene);
        policy.setVersion(nextSeq);
        policy.setStatus("active");
        policy.setRulesJson(request.rulesJson() != null ? request.rulesJson() : "");
        policy.setEffectiveFrom(request.effectiveFrom());
        policy.setEffectiveTo(request.effectiveTo());
        return toView(policyRepository.save(policy));
    }

    /** 删除单条规则（按 policyId），其余规则不受影响 */
    @Transactional
    public void deletePolicy(Long policyId) {
        if (policyId == null || !policyRepository.existsById(policyId)) {
            throw new BizException(BizSceneErrorCode.POLICY_NOT_FOUND);
        }
        policyRepository.deleteById(policyId);
    }

    /** Skill/Agent 保存校验 + 运行时场景解析（Task 6）：仅 active 码可绑/可解析 */
    public boolean isActiveBizScene(String bizScene) {
        if (!StringUtils.hasText(bizScene)) {
            return false;
        }
        return definitionRepository.findById(bizScene.strip())
                .filter(def -> "active".equals(def.getStatus()))
                .isPresent();
    }

    private BizSceneDefinitionEntity requireScene(String bizScene) {
        return definitionRepository.findById(bizScene.strip())
                .orElseThrow(() -> new BizException(BizSceneErrorCode.SCENE_NOT_FOUND));
    }

    private static BizSceneDefinitionView toView(BizSceneDefinitionEntity def) {
        return new BizSceneDefinitionView(
                def.getBizScene(), def.getDisplayName(), def.getDescription(),
                def.getStatus(), def.getTenantId(), def.getSource(), def.getSourceConversationId(),
                def.getApprovedBy(), def.getApprovedAt(), def.getUpdatedAt());
    }

    /** float 列表 → JSON 字符串（description_vector JSON 列）。 */
    private static String jsonVector(List<Float> vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private static BizScenePolicyView toView(BizScenePolicyEntity policy) {
        return new BizScenePolicyView(
                policy.getPolicyId(), policy.getTenantId(), policy.getBizScene(),
                policy.getVersion(), policy.getStatus(), policy.getRulesJson(),
                policy.getEffectiveFrom(), policy.getEffectiveTo(), policy.getUpdatedAt());
    }
}
