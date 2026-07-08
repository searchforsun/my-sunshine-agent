package com.sunshine.orchestrator.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.config.ExpertCoordinatorProperties;
import com.sunshine.orchestrator.peer.PeerSynthesisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertRoundCoordinatorServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGatewayClient llmGatewayClient;

    private ExpertRoundCoordinatorService service;

    @BeforeEach
    void setUp() {
        PeerSynthesisProperties peer = new PeerSynthesisProperties();
        peer.setMinRounds(1);
        peer.setMaxRounds(3);
        ExpertCoordinatorProperties expert = new ExpertCoordinatorProperties();
        service = new ExpertRoundCoordinatorService(llmGatewayClient, peer, expert);
    }

    @Test
    void evaluateContinue_parsesStopDecision() throws Exception {
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"continue\":false,\"reason\":\"观点一致\"}");
        ExpertContinueDecision decision = service.evaluateContinue(
                "是否合规", List.of(new ExpertTranscriptEntry("a", "A", 1, "同意")), 1);
        assertThat(decision.shouldContinue()).isFalse();
        assertThat(decision.reason()).contains("观点一致");
    }

    @Test
    void selectReactiveSpeakers_preservesRosterOrder() throws Exception {
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"expertIds\":[\"finance-expert\",\"policy-expert\"]}");
        List<ExpertCatalogEntry> roster = List.of(
                entry("policy-expert", "制度专家"),
                entry("finance-expert", "财务专家"),
                entry("compliance-expert", "合规专家"));
        List<String> speakers = service.selectReactiveSpeakers(
                "报销合规", roster, List.of(), 2);
        assertThat(speakers).containsExactly("policy-expert", "finance-expert");
    }

    @Test
    void selectReactiveSpeakers_emptyWhenNoObjection() throws Exception {
        when(llmGatewayClient.complete(anyString(), anyString()))
                .thenReturn("{\"expertIds\":[]}");
        List<ExpertCatalogEntry> roster = List.of(
                entry("policy-expert", "制度专家"),
                entry("finance-expert", "财务专家"));
        assertThat(service.selectReactiveSpeakers("q", roster, List.of(), 2)).isEmpty();
    }

    @Test
    void extractJsonObject_stripsMarkdownFence() throws Exception {
        String json = ExpertRoundCoordinatorService.extractJsonObject(
                "说明\n```json\n{\"continue\":true}\n```");
        assertThat(MAPPER.readTree(json).get("continue").asBoolean()).isTrue();
    }

    private static ExpertCatalogEntry entry(String id, String name) {
        return new ExpertCatalogEntry(id, name, "", "prompt", List.of("skill"), List.of(), "[\"*\"]", true);
    }
}
