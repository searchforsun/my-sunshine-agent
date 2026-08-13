package com.sunshine.bizscene.service;

import com.sunshine.bizscene.dto.BizSceneCreateRequest;
import com.sunshine.bizscene.dto.BizSceneDefinitionView;
import com.sunshine.bizscene.dto.BizScenePolicySaveRequest;
import com.sunshine.bizscene.dto.BizScenePolicyView;
import com.sunshine.bizscene.dto.BizSceneUpdateRequest;
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

/** 业务场景 Lab：biz_scene 闭集码表 + 场景 Policy 管理（K2）。运行时禁止新建码；disabled 不可新绑。 */
@Service
@RequiredArgsConstructor
public class BizSceneAdminService {

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
        if (definitionRepository.existsById(code)) {
            throw new BizException(BizSceneErrorCode.SCENE_ALREADY_EXISTS);
        }
        BizSceneDefinitionEntity def = new BizSceneDefinitionEntity();
        def.setBizScene(code);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
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
            if (!"active".equals(status) && !"disabled".equals(status)) {
                throw new BizException(BizSceneErrorCode.INVALID_STATUS);
            }
            def.setStatus(status);
        }
        def.setUpdatedAt(Instant.now());
        return toView(definitionRepository.save(def));
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
                def.getStatus(), def.getTenantId(), def.getUpdatedAt());
    }

    private static BizScenePolicyView toView(BizScenePolicyEntity policy) {
        return new BizScenePolicyView(
                policy.getPolicyId(), policy.getTenantId(), policy.getBizScene(),
                policy.getVersion(), policy.getStatus(), policy.getRulesJson(),
                policy.getEffectiveFrom(), policy.getEffectiveTo(), policy.getUpdatedAt());
    }
}
