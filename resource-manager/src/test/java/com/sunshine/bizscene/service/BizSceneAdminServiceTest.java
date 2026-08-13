package com.sunshine.bizscene.service;

import com.sunshine.bizscene.dto.BizSceneCreateRequest;
import com.sunshine.bizscene.dto.BizScenePolicySaveRequest;
import com.sunshine.bizscene.dto.BizSceneUpdateRequest;
import com.sunshine.bizscene.entity.BizSceneDefinitionEntity;
import com.sunshine.bizscene.entity.BizScenePolicyEntity;
import com.sunshine.bizscene.exception.BizSceneErrorCode;
import com.sunshine.bizscene.repo.BizSceneDefinitionRepository;
import com.sunshine.bizscene.repo.BizScenePolicyRepository;
import com.sunshine.common.core.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BizSceneAdminServiceTest {

    @Mock
    private BizSceneDefinitionRepository definitionRepository;
    @Mock
    private BizScenePolicyRepository policyRepository;

    private BizSceneAdminService service;

    @BeforeEach
    void setUp() {
        service = new BizSceneAdminService(definitionRepository, policyRepository);
    }

    @Test
    void createScene_rejectsBlankCodeAndDuplicate() {
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("  ", "名称", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.CODE_REQUIRED);
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("refund", "", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.DISPLAY_NAME_REQUIRED);
        when(definitionRepository.existsById("refund")).thenReturn(true);
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("refund", "退款", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.SCENE_ALREADY_EXISTS);
    }

    @Test
    void createScene_savesNewActiveScene() {
        BizSceneDefinitionEntity saved = new BizSceneDefinitionEntity();
        saved.setBizScene("refund");
        saved.setDisplayName("退款");
        saved.setStatus("active");
        when(definitionRepository.existsById("refund")).thenReturn(false);
        when(definitionRepository.save(any(BizSceneDefinitionEntity.class))).thenReturn(saved);

        var view = service.createScene(new BizSceneCreateRequest("refund", "退款", "处理退款"));

        assertThat(view.bizScene()).isEqualTo("refund");
        assertThat(view.status()).isEqualTo("active");
        verify(definitionRepository).save(any(BizSceneDefinitionEntity.class));
    }

    @Test
    void updateScene_rejectsInvalidStatus() {
        BizSceneDefinitionEntity def = new BizSceneDefinitionEntity();
        def.setBizScene("refund");
        def.setStatus("active");
        when(definitionRepository.findById("refund")).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> service.updateScene("refund",
                new BizSceneUpdateRequest("退款", null, "unknown")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.INVALID_STATUS);
    }

    @Test
    void createPolicy_rejectsMissingOrRetiredScene() {
        when(definitionRepository.findById("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPolicy("default",
                new BizScenePolicySaveRequest("unknown", "{}", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.SCENE_NOT_FOUND);

        BizSceneDefinitionEntity retired = new BizSceneDefinitionEntity();
        retired.setBizScene("legacy");
        retired.setStatus("retired");
        when(definitionRepository.findById("legacy")).thenReturn(Optional.of(retired));
        assertThatThrownBy(() -> service.createPolicy("default",
                new BizScenePolicySaveRequest("legacy", "{}", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.SCENE_NOT_ACTIVE);
    }

    @Test
    void createPolicy_monotonicVersion() {
        BizSceneDefinitionEntity active = new BizSceneDefinitionEntity();
        active.setBizScene("compliance-review");
        active.setStatus("active");
        when(definitionRepository.findById("compliance-review")).thenReturn(Optional.of(active));
        BizScenePolicyEntity v2 = new BizScenePolicyEntity();
        v2.setPolicyId(2L);
        v2.setBizScene("compliance-review");
        v2.setVersion(2);
        when(policyRepository.findByBizSceneOrderByVersionDesc("compliance-review"))
                .thenReturn(List.of(v2));
        when(policyRepository.save(any(BizScenePolicyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createPolicy("default",
                new BizScenePolicySaveRequest("compliance-review", "{\"x\":1}", null, null));

        assertThat(view.version()).isEqualTo(3);
    }

    @Test
    void isActiveBizScene_retiredCodeCannotBind() {
        BizSceneDefinitionEntity retired = new BizSceneDefinitionEntity();
        retired.setBizScene("legacy");
        retired.setStatus("retired");
        when(definitionRepository.findById("legacy")).thenReturn(Optional.of(retired));
        when(definitionRepository.findById("refund")).thenReturn(Optional.empty());

        assertThat(service.isActiveBizScene("legacy")).isFalse();
        assertThat(service.isActiveBizScene("refund")).isFalse();
        assertThat(service.isActiveBizScene("  ")).isFalse();
    }

    @Test
    void isActiveBizScene_activeCodeTrue() {
        BizSceneDefinitionEntity active = new BizSceneDefinitionEntity();
        active.setBizScene("policy-qa");
        active.setStatus("active");
        when(definitionRepository.findById(anyString())).thenReturn(Optional.of(active));

        assertThat(service.isActiveBizScene("policy-qa")).isTrue();
    }
}
