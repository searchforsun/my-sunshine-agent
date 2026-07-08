package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.peer.PeerMsgSupport;
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
 * <p>专家 Sub-Agent 经 {@link ExpertSpeakHook} 在工具调用期间刷新 expert 步 active；
 * 正文在整轮 {@link ReActAgent#call} 完成后一次性写入 Timeline（ReAct 工具 acting 阶段无 REASONING 流）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpertHubEngine {
    private final ExpertPeerAgentFactory expertPeerAgentFactory;
    private final PromptComposer promptComposer;

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

    /** 整轮 ReAct（含工具）同步执行；工具期间 Hook 刷新 active，终态正文一次性下发 */
    private String invokeAgent(
            String hubRunId,
            ReActAgent agent,
            String userQuery,
            List<String> contextBlocks,
            ExpertCatalogEntry expert,
            ExpertTranscriptEntry pendingEntry,
            String assistantMessageId,
            ExpertSpeakCallback callback) {
        List<Msg> inputs = promptComposer.composeReactInputs(
                PromptComposeRequest.forReact(
                        MemoryContext.forSubAgent(),
                        userQuery,
                        expert.primarySkillId(),
                        contextBlocks,
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
        try {
            Msg result = agent.call(inputs).block();
            String text = PeerMsgSupport.extractText(result);
            if (StringUtils.hasText(text) && callback != null) {
                callback.onSpeakDelta(pendingEntry, text);
            }
            if (StringUtils.hasText(text)) {
                log.info("[ExpertHubEngine] expert={} 发言完成 {} 字", expert.id(), text.length());
            }
            return text != null ? text : "";
        } catch (Exception e) {
            log.warn("[ExpertHubEngine] call failed expert={}: {}", expert.id(), e.getMessage());
            return "";
        } finally {
            StepEventBridge.clear(bridgeId);
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
