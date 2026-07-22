package com.sunshine.orchestrator.context.audit;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextAuditServiceTest {

    @Mock
    private UserContextStateRepository l2Repository;
    @Mock
    private ConversationContextL1Repository l1Repository;
    @Mock
    private ConversationContextL1Store l1Store;
    @Mock
    private LlmGatewayClient llmGatewayClient;
    @Mock
    private PromptCatalogHolder catalogHolder;

    private ContextAuditService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        ContextProperties props = new ContextProperties();
        props.getMaintenance().setAuditEnabled(true);
        props.getMaintenance().setAuditExtractDebounceMs(0);
        service = new ContextAuditService(
                props, l2Repository, l1Repository, l1Store, llmGatewayClient, catalogHolder);
        now = Instant.parse("2026-07-22T08:00:00Z");
    }

    @Test
    void ruleDedupeActiveSameKey_voidsLosers() {
        UserContextStateEntity keep = l2("a1", "fact", "city", "上海", 0.9, now);
        UserContextStateEntity lose = l2("a2", "fact", "city", "北京", 0.5, now.minusSeconds(60));
        when(l2Repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(keep, lose));

        int voided = service.ruleDedupeActiveSameKey("u1", "default", now);

        assertThat(voided).isEqualTo(1);
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(l2Repository).save(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo("a2");
        assertThat(cap.getValue().getStatus()).isEqualTo("void");
    }

    @Test
    void parseL2Decision_ignoresHallucinatedIds_whenApplying() {
        UserContextStateEntity only = l2("real-1", "fact", "city", "上海", 0.9, now);
        when(l2Repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(only));
        when(l1Repository.findByUserIdAndTenantId("u1", "default")).thenReturn(List.of());
        when(catalogHolder.requireText(ContextAuditService.L2_AUDIT_PROMPT)).thenReturn("sys");
        when(llmGatewayClient.complete(eq("sys"), anyString()))
                .thenReturn("{\"voidIds\":[\"hallucinated\",\"real-1\"],\"conflictIds\":[],\"reasons\":{}}");
        when(l2Repository.findById("real-1")).thenReturn(Optional.of(only));

        ContextAuditService.AuditStats stats = service.auditUserLight("u1", "default", false);

        assertThat(stats.voided()).isEqualTo(1);
        verify(l2Repository, never()).findById("hallucinated");
        ArgumentCaptor<UserContextStateEntity> cap = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(l2Repository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().stream().anyMatch(e ->
                "real-1".equals(e.getId()) && "void".equals(e.getStatus()))).isTrue();
    }

    @Test
    void parseL2Decision_marksConflict() {
        UserContextStateEntity a = l2("c1", "preference", "tone", "正式", 0.8, now);
        when(l2Repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(a));
        when(l1Repository.findByUserIdAndTenantId("u1", "default")).thenReturn(List.of());
        when(catalogHolder.requireText(ContextAuditService.L2_AUDIT_PROMPT)).thenReturn("sys");
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"voidIds\":[],\"conflictIds\":[\"c1\"],\"reasons\":{\"c1\":\"暧昧\"}}");
        when(l2Repository.findById("c1")).thenReturn(Optional.of(a));

        ContextAuditService.AuditStats stats = service.auditUserLight("u1", "default", false);

        assertThat(stats.conflicted()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo("conflict");
    }

    @Test
    void llmFailure_doesNotThrow() {
        UserContextStateEntity a = l2("x1", "fact", "k", "v", 0.8, now);
        when(l2Repository.findByUserIdAndTenantIdAndStatus("u1", "default", "active"))
                .thenReturn(List.of(a));
        when(l1Repository.findByUserIdAndTenantId("u1", "default")).thenReturn(List.of());
        when(catalogHolder.requireText(ContextAuditService.L2_AUDIT_PROMPT)).thenReturn("sys");
        when(llmGatewayClient.complete(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        ContextAuditService.AuditStats stats = service.auditUserLight("u1", "default", false);

        assertThat(stats.voided()).isZero();
        assertThat(stats.conflicted()).isZero();
    }

    @Test
    void parseL1Decision_removesMidKeys() {
        String raw = "{\"removeMidKeys\":{\"conv1\":[\"m1\",\"ghost\"]},\"farSummaryByConv\":{\"conv1\":\"修订\"},\"notes\":\"\"}";
        ContextAuditService.L1AuditDecision d = ContextAuditService.parseL1Decision(raw);
        assertThat(d.removeMidKeys()).containsEntry("conv1", List.of("m1", "ghost"));
        assertThat(d.farSummaryByConv()).containsEntry("conv1", "修订");
    }

    private static UserContextStateEntity l2(
            String id, String kind, String key, String value, double conf, Instant updated) {
        UserContextStateEntity e = new UserContextStateEntity();
        e.setId(id);
        e.setUserId("u1");
        e.setTenantId("default");
        e.setKind(kind);
        e.setStateKey(key);
        e.setStateValue(value);
        e.setConfidence(conf);
        e.setStatus("active");
        e.setUpdatedAt(updated);
        e.setCreatedAt(updated);
        return e;
    }
}
