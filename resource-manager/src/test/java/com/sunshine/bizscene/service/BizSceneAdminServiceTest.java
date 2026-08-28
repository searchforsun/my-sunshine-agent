package com.sunshine.bizscene.service;

import com.sunshine.bizscene.dto.BizSceneCreateRequest;
import com.sunshine.bizscene.dto.BizScenePolicySaveRequest;
import com.sunshine.bizscene.dto.BizSceneUpdateRequest;
import com.sunshine.bizscene.dto.BizSceneVectorRequest;
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
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("  ", "名称", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.CODE_REQUIRED);
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("refund", "", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.DISPLAY_NAME_REQUIRED);
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("Refund", "退款", null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.INVALID_CODE_FORMAT);
        when(definitionRepository.existsById("refund")).thenReturn(true);
        assertThatThrownBy(() -> service.createScene(new BizSceneCreateRequest("refund", "退款", null, null, null)))
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

        var view = service.createScene(new BizSceneCreateRequest("refund", "退款", "处理退款", null, null));

        assertThat(view.bizScene()).isEqualTo("refund");
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.source()).isEqualTo("manual");
        verify(definitionRepository).save(any(BizSceneDefinitionEntity.class));
    }

    @Test
    void createScene_autoSource_initialPendingReview() {
        BizSceneDefinitionEntity saved = new BizSceneDefinitionEntity();
        saved.setBizScene("refund-inquiry");
        saved.setDisplayName("退款咨询");
        saved.setStatus("pending_review");
        saved.setSource("auto");
        when(definitionRepository.existsById("refund-inquiry")).thenReturn(false);
        when(definitionRepository.save(any(BizSceneDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createScene(
                new BizSceneCreateRequest("refund-inquiry", "退款咨询", "用户咨询退款进度与原因", "auto", "conv-1"));

        assertThat(view.status()).isEqualTo("pending_review");
        assertThat(view.source()).isEqualTo("auto");
        assertThat(view.sourceConversationId()).isEqualTo("conv-1");
    }

    @Test
    void updateScene_approveAuto_recordsApprover() {
        BizSceneDefinitionEntity def = new BizSceneDefinitionEntity();
        def.setBizScene("refund-inquiry");
        def.setStatus("pending_review");
        def.setSource("auto");
        when(definitionRepository.findById("refund-inquiry")).thenReturn(Optional.of(def));
        when(definitionRepository.save(any(BizSceneDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateScene("refund-inquiry",
                new BizSceneUpdateRequest(null, null, "active", "ops-user"));

        assertThat(view.status()).isEqualTo("active");
        assertThat(view.approvedBy()).isEqualTo("ops-user");
        assertThat(view.approvedAt()).isNotNull();
    }

    @Test
    void updateVector_setsJsonVector() {
        BizSceneDefinitionEntity def = new BizSceneDefinitionEntity();
        def.setBizScene("refund");
        when(definitionRepository.findById("refund")).thenReturn(Optional.of(def));
        when(definitionRepository.save(any(BizSceneDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateVector("refund", new BizSceneVectorRequest(List.of(0.1f, -0.2f, 0.3f)));

        assertThat(def.getDescriptionVector()).isEqualTo("[0.1,-0.2,0.3]");
    }

    @Test
    void updateScene_rejectsInvalidStatus() {
        BizSceneDefinitionEntity def = new BizSceneDefinitionEntity();
        def.setBizScene("refund");
        def.setStatus("active");
        when(definitionRepository.findById("refund")).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> service.updateScene("refund",
                new BizSceneUpdateRequest("退款", null, "unknown", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.INVALID_STATUS);
    }

    @Test
    void createPolicy_rejectsMissingOrDisabledScene() {
        when(definitionRepository.findById("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPolicy("default",
                new BizScenePolicySaveRequest("unknown", "{}", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(BizSceneErrorCode.SCENE_NOT_FOUND);

        BizSceneDefinitionEntity disabled = new BizSceneDefinitionEntity();
        disabled.setBizScene("legacy");
        disabled.setStatus("disabled");
        when(definitionRepository.findById("legacy")).thenReturn(Optional.of(disabled));
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
    void isActiveBizScene_disabledCodeCannotBind() {
        BizSceneDefinitionEntity disabled = new BizSceneDefinitionEntity();
        disabled.setBizScene("legacy");
        disabled.setStatus("disabled");
        when(definitionRepository.findById("legacy")).thenReturn(Optional.of(disabled));
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
