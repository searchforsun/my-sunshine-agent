package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.peer.PeerMsgSupport;
import com.sunshine.orchestrator.peer.PeerSynthesisProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对等专家 Hub — 反应式轮次：首轮全员发言，后续轮仅异议/补材料专家；每轮结束可提前收敛。
 * AS2_P0_PEER_SEQUENTIAL：去 MsgHub，专家顺序调用，上下文经 contextBlocks/transcript 显式传递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpertHubEngine {
    /**
     * spec §P0：顺序桥标记，P6 反应式恢复后删除。
     * 注意：MsgHub autoBroadcast 原会写入 SUB agent memory；本桥仅经 contextBlocks 传递，SUB memory 不再跨轮累积。P6 恢复反应式时需评估是否需 memory 维度。
     */
    public static final String AS2_P0_PEER_SEQUENTIAL = "AS2_P0_PEER_SEQUENTIAL";

    private final ExpertPeerAgentFactory expertPeerAgentFactory;
    private final ToolSetResolver toolSetResolver;
    private final PromptComposer promptComposer;
    private final ExpertSpeakStreamer expertSpeakStreamer;
    private final PeerSynthesisProperties peerProperties;
    private final ExpertRoundCoordinatorService roundCoordinator;
    private final PromptCatalogHolder promptCatalogHolder;

    public ExpertHubResult run(
            List<ExpertCatalogEntry> roster,
            String userQuery,
            int sessionMaxRounds,
            String assistantMessageId,
            ExpertSpeakCallback callback) {
        return run(roster, userQuery, sessionMaxRounds, assistantMessageId, callback, null, null);
    }

    public ExpertHubResult run(
            List<ExpertCatalogEntry> roster,
            String userQuery,
            int sessionMaxRounds,
            String assistantMessageId,
            ExpertSpeakCallback callback,
            String userId,
            String tenantId) {
        if (roster == null || roster.size() < 2) {
            throw new IllegalStateException("expert roster must have at least 2 members");
        }
        int minRounds = Math.max(1, peerProperties.getMinRounds());
        int effectiveMax = ExpertSessionRounds.clampSessionMax(
                sessionMaxRounds, minRounds, peerProperties.getMaxRounds());
        String runId = UUID.randomUUID().toString();
        List<ExpertTranscriptEntry> transcript = new ArrayList<>();
        Map<String, Integer> speakSeq = new HashMap<>();
        Map<String, ReActAgent> agentByExpertId = new LinkedHashMap<>();
        for (ExpertCatalogEntry expert : roster) {
            agentByExpertId.put(expert.id(), createAgent(runId, expert, userId, tenantId));
        }
        List<String> contextBlocks = new ArrayList<>();
        contextBlocks.add("用户问题：\n" + userQuery);
        boolean othersSpoke = false;
        for (int round = 1; round <= effectiveMax; round++) {
            List<ExpertCatalogEntry> speakers = resolveSpeakers(roster, userQuery, transcript, round);
            if (speakers.isEmpty()) {
                log.info("[ExpertHubEngine] round {} 无发言人，提前结束 runId={}", round, runId);
                break;
            }
            for (ExpertCatalogEntry expert : speakers) {
                ReActAgent peer = agentByExpertId.get(expert.id());
                int seq = speakSeq.getOrDefault(expert.id(), 0) + 1;
                boolean responding = othersSpoke && seq > 1;
                ExpertTranscriptEntry pending = new ExpertTranscriptEntry(
                        expert.id(), expert.displayName(), seq, "");
                if (callback != null) {
                    callback.onSpeak(pending, "running", responding);
                }
                String reply = invokeAgent(
                        runId, peer, userQuery, contextBlocks, expert, pending,
                        assistantMessageId, callback);
                if (!StringUtils.hasText(reply)) {
                    if (callback != null) {
                        callback.onSpeak(pending, "done", responding);
                    }
                    continue;
                }
                speakSeq.put(expert.id(), seq);
                ExpertTranscriptEntry entry = appendToTranscript(transcript, contextBlocks, expert, seq, reply);
                othersSpoke = true;
                if (callback != null) {
                    callback.onSpeak(entry, "done", responding);
                }
            }
            if (round >= minRounds && round < effectiveMax) {
                ExpertContinueDecision decision = roundCoordinator.evaluateContinue(userQuery, transcript, round);
                if (!decision.shouldContinue()) {
                    log.info("[ExpertHubEngine] round {} 收敛：{} runId={}", round, decision.reason(), runId);
                    break;
                }
            }
        }
        return new ExpertHubResult(runId, transcript);
    }

    /** AS2_P0_PEER_SEQUENTIAL：去 MsgHub，专家间上下文经 transcript/contextBlocks 显式传递（spec §3 / §P6 G-a 路径 1） */
    private ExpertTranscriptEntry appendToTranscript(
            List<ExpertTranscriptEntry> transcript,
            List<String> contextBlocks,
            ExpertCatalogEntry expert,
            int speakSeq,
            String reply) {
        ExpertTranscriptEntry entry = new ExpertTranscriptEntry(
                expert.id(), expert.displayName(), speakSeq, reply);
        transcript.add(entry);
        contextBlocks.add(PeerMsgSupport.formatTranscriptBlock(expert.displayName(), reply));
        return entry;
    }

    private List<ExpertCatalogEntry> resolveSpeakers(
            List<ExpertCatalogEntry> roster,
            String userQuery,
            List<ExpertTranscriptEntry> transcript,
            int round) {
        if (round <= 1) {
            return roster;
        }
        List<String> ids = roundCoordinator.selectReactiveSpeakers(userQuery, roster, transcript, round);
        if (ids.isEmpty()) {
            return List.of();
        }
        return roster.stream().filter(e -> ids.contains(e.id())).toList();
    }

    /** 阶段1 ReAct 工具检索 + 阶段2 Gateway 流式发言 */
    private String invokeAgent(
            String hubRunId,
            ReActAgent agent,
            String userQuery,
            List<String> contextBlocks,
            ExpertCatalogEntry expert,
            ExpertTranscriptEntry pendingEntry,
            String assistantMessageId,
            ExpertSpeakCallback callback) {
        List<String> gatherContexts = new ArrayList<>(contextBlocks);
        String gatherInstruction = promptCatalogHolder.requireText("peer.gather-instruction");
        if (StringUtils.hasText(gatherInstruction)) {
            gatherContexts.add(gatherInstruction.strip());
        }
        List<Msg> inputs = promptComposer.composeReactInputs(
                PromptComposeRequest.forReact(
                        AssembledContext.forSubAgent(),
                        userQuery,
                        expert.primarySkillId(),
                        gatherContexts,
                        false));
        String bridgeId = "sub-" + hubRunId + "-" + expert.id();
        if (StringUtils.hasText(assistantMessageId)) {
            StepEventBridge.bindHitlBridge(bridgeId, assistantMessageId.strip(), false);
        }
        StepEventBridge.bindExpertSpeakSink(bridgeId, token -> {
            if (token != null && token.isStep()) {
                maybeNotifyToolProgress(token.step(), pendingEntry, callback);
            }
        });
        String gatheredContext = "";
        try {
            Msg result = agent.call(inputs).block();
            gatheredContext = PeerMsgSupport.extractText(result);
        } catch (Exception e) {
            log.warn("[ExpertHubEngine] gather failed expert={}: {}", expert.id(), e.getMessage());
            StepEventBridge.clear(bridgeId);
            return "";
        } finally {
            StepEventBridge.clear(bridgeId);
        }
        if (callback != null) {
            callback.onSpeakActive(pendingEntry, ExpertStepLabels.expertActive(expert.displayName()) + "…");
        }
        StringBuilder speakText = new StringBuilder();
        try {
            expertSpeakStreamer.streamSpeak(expert, userQuery, contextBlocks, gatheredContext)
                    .doOnNext(token -> appendSpeakToken(token, pendingEntry, callback, speakText))
                    .blockLast();
        } catch (Exception e) {
            log.warn("[ExpertHubEngine] speak stream failed expert={}: {}", expert.id(), e.getMessage());
            return speakText.toString();
        }
        String text = speakText.toString();
        if (StringUtils.hasText(text)) {
            log.info("[ExpertHubEngine] expert={} 发言完成 {} 字", expert.id(), text.length());
        }
        return text;
    }

    /** 空白 token（仅 \\n / 空格）须下发，hasText 会误丢弃导致 Markdown 结构断裂 */
    private static void appendSpeakToken(
            StreamToken token,
            ExpertTranscriptEntry pendingEntry,
            ExpertSpeakCallback callback,
            StringBuilder speakText) {
        if (token == null || !token.isContent()) {
            return;
        }
        String piece = token.text();
        if (piece == null || piece.isEmpty()) {
            return;
        }
        speakText.append(piece);
        if (callback != null) {
            callback.onSpeakDelta(pendingEntry, piece);
        }
    }

    private static void maybeNotifyToolProgress(
            ProcessingStep step,
            ExpertTranscriptEntry pendingEntry,
            ExpertSpeakCallback callback) {
        if (callback == null || step == null || step.phase() == null) {
            return;
        }
        if (!step.phase().startsWith("tool")) {
            return;
        }
        String lifecycle = step.lifecycle();
        if (!"running".equals(lifecycle) && !"pending".equals(lifecycle)) {
            return;
        }
        String label = step.label();
        if (!StringUtils.hasText(label)) {
            return;
        }
        callback.onSpeakActive(pendingEntry, label + "…");
    }

    private ReActAgent createAgent(
            String runId, ExpertCatalogEntry expert, String userId, String tenantId) {
        return expertPeerAgentFactory.create(buildPeerRequest(runId, expert, userId, tenantId));
    }

    /** package-private for tests — 专家 SUB 请求须带 userId/tenantId，否则 SDK 工具 502 */
    AgentRunRequest buildPeerRequest(
            String runId, ExpertCatalogEntry expert, String userId, String tenantId) {
        List<String> toolIds = resolveToolWhitelist(expert);
        return new AgentRunRequest(
                AgentRole.SUB,
                runId + "-" + expert.id(),
                runId,
                AssembledContext.forSubAgent(),
                "",
                List.of(),
                userId,
                tenantId,
                null,
                expert.primarySkillId(),
                toolIds,
                expert.systemPrompt(),
                2,
                TimelineBinding.SUB_COMPRESSED,
                false,
                null,
                null,
                0);
    }

    /** package-private for tests */
    List<String> resolveToolWhitelist(ExpertCatalogEntry expert) {
        List<String> parsed = ExpertToolsJson.parse(expert != null ? expert.toolsJson() : null);
        if (ExpertToolsJson.isStarAll(parsed)) {
            return toolSetResolver.resolveAllEnabledTools(null);
        }
        return parsed;
    }

    public record ExpertHubResult(String runId, List<ExpertTranscriptEntry> transcript) {
    }
}
