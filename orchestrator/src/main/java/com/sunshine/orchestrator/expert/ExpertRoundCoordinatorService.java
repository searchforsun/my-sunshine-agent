package com.sunshine.orchestrator.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.peer.PeerMsgSupport;
import com.sunshine.orchestrator.peer.PeerSynthesisProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hub 轮次策略：复杂度上限、每轮继续判定、反应式选人（第 2 轮起）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpertRoundCoordinatorService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LlmGatewayClient llmGatewayClient;
    private final PeerSynthesisProperties peerProperties;
    private final PromptCatalogHolder promptCatalogHolder;

    public int resolveSessionMaxRounds(Integer coordinatorSuggested, int minRounds, int globalMaxRounds) {
        return ExpertSessionRounds.clampSessionMax(coordinatorSuggested, minRounds, globalMaxRounds);
    }

    /** 用户 $ 显式绑定时，单独评估本轮讨论上限 */
    public int assessSessionMaxRounds(String userQuery, List<String> rosterIds) {
        if (!StringUtils.hasText(userQuery) || rosterIds == null || rosterIds.isEmpty()) {
            return peerProperties.getMaxRounds();
        }
        String rosterLine = String.join(", ", rosterIds);
        String user = "用户问题：\n" + userQuery.strip()
                + "\n\n已选专家 id：" + rosterLine
                + "\n\n全局轮次上限：" + peerProperties.getMaxRounds();
        String raw = llmGatewayClient.complete(promptCatalogHolder.requireText("expert.complexity-prompt"), user);
        try {
            JsonNode node = MAPPER.readTree(extractJsonObject(raw));
            int parsed = ExpertSessionRounds.parseMaxRoundsNode(node);
            if (parsed > 0) {
                return resolveSessionMaxRounds(parsed, peerProperties.getMinRounds(), peerProperties.getMaxRounds());
            }
        } catch (Exception e) {
            log.warn("[ExpertRoundCoordinator] complexity parse failed: {}", e.getMessage());
        }
        return resolveSessionMaxRounds(2, peerProperties.getMinRounds(), peerProperties.getMaxRounds());
    }

    /** 已完成 roundNo 轮后，是否继续（仅当 roundNo >= minRounds 且 roundNo < sessionMax 时调用） */
    public ExpertContinueDecision evaluateContinue(
            String userQuery,
            List<ExpertTranscriptEntry> transcript,
            int completedRound) {
        String user = buildRoundUserPayload(userQuery, transcript, completedRound);
        String raw = llmGatewayClient.complete(promptCatalogHolder.requireText("peer.round-continue-prompt"), user);
        try {
            JsonNode node = MAPPER.readTree(extractJsonObject(raw));
            boolean cont = node.has("continue") && node.get("continue").asBoolean(false);
            String reason = node.has("reason") ? node.get("reason").asText("") : "";
            if (!cont) {
                return ExpertContinueDecision.stop(StringUtils.hasText(reason) ? reason.strip() : "讨论已收敛");
            }
            return ExpertContinueDecision.proceed(StringUtils.hasText(reason) ? reason.strip() : "需进一步交叉验证");
        } catch (Exception e) {
            log.warn("[ExpertRoundCoordinator] continue parse failed round={}: {}", completedRound, e.getMessage());
            return ExpertContinueDecision.stop("轮次判定解析失败，进入汇总");
        }
    }

    /** 第 2 轮起：仅返回仍有异议或需补材料的专家 id（roster 子集，保持召集顺序） */
    public List<String> selectReactiveSpeakers(
            String userQuery,
            List<ExpertCatalogEntry> roster,
            List<ExpertTranscriptEntry> transcript,
            int nextRound) {
        if (roster == null || roster.size() < 2) {
            return List.of();
        }
        String catalog = roster.stream()
                .map(e -> "- " + e.id() + ": " + e.displayName())
                .collect(Collectors.joining("\n"));
        String user = buildRoundUserPayload(userQuery, transcript, nextRound - 1)
                + "\n\n本轮候选专家（仅可从下列 id 中选择）：\n" + catalog
                + "\n\n即将开始第 " + nextRound + " 轮发言。";
        String raw = llmGatewayClient.complete(promptCatalogHolder.requireText("peer.round-speakers-prompt"), user);
        try {
            JsonNode node = MAPPER.readTree(extractJsonObject(raw));
            Set<String> rosterIds = roster.stream().map(ExpertCatalogEntry::id).collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> picked = new ArrayList<>();
            if (node.has("expertIds") && node.get("expertIds").isArray()) {
                node.get("expertIds").forEach(n -> {
                    if (n.isTextual()) {
                        String id = n.asText().strip();
                        if (rosterIds.contains(id) && !picked.contains(id)) {
                            picked.add(id);
                        }
                    }
                });
            }
            return orderByRoster(roster, picked);
        } catch (Exception e) {
            log.warn("[ExpertRoundCoordinator] reactive speakers parse failed round={}: {}", nextRound, e.getMessage());
            return List.of();
        }
    }

    static List<String> orderByRoster(List<ExpertCatalogEntry> roster, List<String> speakerIds) {
        if (speakerIds == null || speakerIds.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>(speakerIds);
        List<String> ordered = new ArrayList<>();
        for (ExpertCatalogEntry entry : roster) {
            if (wanted.contains(entry.id())) {
                ordered.add(entry.id());
            }
        }
        return ordered;
    }

    private String buildRoundUserPayload(String userQuery, List<ExpertTranscriptEntry> transcript, int completedRound) {
        return "用户问题：\n" + (userQuery != null ? userQuery : "")
                + "\n\n已完成讨论轮次：" + completedRound
                + "\n\n讨论记录：\n" + formatTranscript(transcript);
    }

    private static String formatTranscript(List<ExpertTranscriptEntry> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "(暂无发言)";
        }
        return transcript.stream()
                .filter(e -> StringUtils.hasText(e.content()))
                .map(e -> PeerMsgSupport.formatTranscriptBlock(e.displayName(), e.content()))
                .collect(Collectors.joining("\n\n"));
    }

    static String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{}";
        }
        String trimmed = raw.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
