package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.peer.PeerMsgSupport;
import com.sunshine.orchestrator.peer.PeerSynthesisProperties;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.pipeline.MsgHub;
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
 * 对等专家 MsgHub — 无仲裁角色，按 Hub 轮次发言。
 * <p>两阶段：阶段1 {@link ReActAgent#call} 工具检索（{@link ExpertSpeakHook} 刷 active）；
 * 阶段2 {@link ExpertSpeakStreamer} Gateway 直链 token 流写入 expert 步 {@code step_delta(result)}；空白 token 须保留（勿用 hasText 过滤）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpertHubEngine {
    private final ExpertPeerAgentFactory expertPeerAgentFactory;
    private final PromptComposer promptComposer;
    private final ExpertSpeakStreamer expertSpeakStreamer;
    private final PeerSynthesisProperties peerProperties;

    public ExpertHubResult run(
            List<ExpertCatalogEntry> roster,
            String userQuery,
            int maxRounds,
            String assistantMessageId,
            ExpertSpeakCallback callback) {
        if (roster == null || roster.size() < 2) {
            throw new IllegalStateException("expert roster must have at least 2 members");
        }
        String runId = UUID.randomUUID().toString();
        List<ExpertTranscriptEntry> transcript = new ArrayList<>();
        Map<String, Integer> speakSeq = new HashMap<>();
        Map<ReActAgent, ExpertCatalogEntry> agentExpert = new LinkedHashMap<>();
        List<ReActAgent> peers = new ArrayList<>();
        for (ExpertCatalogEntry expert : roster) {
            ReActAgent agent = createAgent(runId, expert);
            peers.add(agent);
            agentExpert.put(agent, expert);
        }
        List<String> contextBlocks = new ArrayList<>();
        contextBlocks.add("用户问题：\n" + userQuery);
        boolean othersSpoke = false;
        try (MsgHub hub = MsgHub.builder()
                .name("expert-" + runId)
                .participants(peers.stream().map(AgentBase.class::cast).toList())
                .enableAutoBroadcast(true)
                .build()) {
            hub.enter().block();
            for (int round = 0; round < maxRounds; round++) {
                for (ReActAgent peer : peers) {
                    ExpertCatalogEntry expert = agentExpert.get(peer);
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
                    ExpertTranscriptEntry entry = new ExpertTranscriptEntry(
                            expert.id(), expert.displayName(), seq, reply);
                    transcript.add(entry);
                    contextBlocks.add(PeerMsgSupport.formatTranscriptBlock(expert.displayName(), reply));
                    hub.broadcast(Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .name(expert.displayName())
                            .content(List.of(TextBlock.builder().text(reply).build()))
                            .build()).block();
                    othersSpoke = true;
                    if (callback != null) {
                        callback.onSpeak(entry, "done", responding);
                    }
                }
            }
            hub.exit().block();
        } catch (Exception e) {
            log.warn("[ExpertHubEngine] MsgHub 执行异常 runId={}: {}", runId, e.getMessage());
            throw e;
        }
        return new ExpertHubResult(runId, transcript);
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
        if (StringUtils.hasText(peerProperties.getGatherInstruction())) {
            gatherContexts.add(peerProperties.getGatherInstruction().strip());
        }
        List<Msg> inputs = promptComposer.composeReactInputs(
                PromptComposeRequest.forReact(
                        MemoryContext.forSubAgent(),
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

    private ReActAgent createAgent(String runId, ExpertCatalogEntry expert) {
        AgentRunRequest request = new AgentRunRequest(
                AgentRole.SUB,
                runId + "-" + expert.id(),
                runId,
                MemoryContext.forSubAgent(),
                "",
                List.of(),
                null,
                null,
                null,
                expert.primarySkillId(),
                null,
                expert.systemPrompt(),
                2,
                TimelineBinding.SUB_COMPRESSED,
                false);
        return expertPeerAgentFactory.create(request);
    }

    public record ExpertHubResult(String runId, List<ExpertTranscriptEntry> transcript) {
    }
}
