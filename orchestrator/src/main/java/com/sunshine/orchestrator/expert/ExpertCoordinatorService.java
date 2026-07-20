package com.sunshine.orchestrator.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.catalog.ExpertCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.ExpertCatalogService;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.peer.PeerSynthesisProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpertCoordinatorService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ExpertCatalogService expertCatalogService;
    private final LlmGatewayClient llmGatewayClient;
    private final ExpertRoundCoordinatorService roundCoordinator;
    private final PeerSynthesisProperties peerProperties;
    private final PromptCatalogHolder promptCatalogHolder;

    public ExpertRoster resolve(List<String> explicitIds, String query) {
        List<String> normalized = normalizeExplicit(explicitIds);
        if (!normalized.isEmpty()) {
            int sessionMax = roundCoordinator.assessSessionMaxRounds(query, normalized);
            return new ExpertRoster(normalized, null, sessionMax);
        }
        return selectByLlm(query);
    }

    private List<String> normalizeExplicit(List<String> explicitIds) {
        if (explicitIds == null || explicitIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String raw : explicitIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String id = raw.strip();
            if (!expertCatalogService.isKnownExpert(id)) {
                throw new BizException(OrchestratorErrorCode.EXPERT_NOT_FOUND);
            }
            ids.add(id);
        }
        List<String> list = new ArrayList<>(ids);
        if (!list.isEmpty() && list.size() < 2) {
            throw new BizException(OrchestratorErrorCode.EXPERT_ROSTER_TOO_SMALL);
        }
        return list;
    }

    private ExpertRoster selectByLlm(String query) {
        List<ExpertCatalogIndexEntry> candidates = expertCatalogService.indexEntries();
        if (candidates.size() < 2) {
            throw new BizException(OrchestratorErrorCode.EXPERT_ROSTER_TOO_SMALL);
        }
        String catalog = candidates.stream()
                .map(e -> "- " + e.id() + ": " + e.displayName() + " — " + (e.description() != null ? e.description() : ""))
                .collect(Collectors.joining("\n"));
        String user = "用户问题：\n" + query + "\n\n候选专家：\n" + catalog;
        String raw = llmGatewayClient.complete(promptCatalogHolder.requireText("expert.coordinator-prompt"), user);
        try {
            JsonNode node = MAPPER.readTree(raw);
            List<String> ids = new ArrayList<>();
            if (node.has("expertIds") && node.get("expertIds").isArray()) {
                node.get("expertIds").forEach(n -> {
                    if (n.isTextual() && expertCatalogService.isKnownExpert(n.asText())) {
                        ids.add(n.asText().strip());
                    }
                });
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>(ids);
            List<String> roster = new ArrayList<>(unique);
            if (roster.size() < 2) {
                roster = candidates.stream().limit(Math.min(2, candidates.size())).map(ExpertCatalogIndexEntry::id).toList();
            }
            if (roster.size() > 4) {
                roster = roster.subList(0, 4);
            }
            String reason = node.has("reason") ? node.get("reason").asText("") : "";
            int parsedMax = ExpertSessionRounds.parseMaxRoundsNode(node);
            Integer sessionMax = parsedMax > 0
                    ? roundCoordinator.resolveSessionMaxRounds(
                            parsedMax, peerProperties.getMinRounds(), peerProperties.getMaxRounds())
                    : null;
            return new ExpertRoster(
                    roster,
                    StringUtils.hasText(reason) ? reason.strip() : null,
                    sessionMax);
        } catch (Exception e) {
            log.warn("[ExpertCoordinator] parse failed, fallback first two: {}", e.getMessage());
            List<String> fallback = candidates.stream().limit(2).map(ExpertCatalogIndexEntry::id).toList();
            return new ExpertRoster(
                    fallback,
                    "已按目录默认召集专家",
                    roundCoordinator.resolveSessionMaxRounds(
                            2, peerProperties.getMinRounds(), peerProperties.getMaxRounds()));
        }
    }
}
