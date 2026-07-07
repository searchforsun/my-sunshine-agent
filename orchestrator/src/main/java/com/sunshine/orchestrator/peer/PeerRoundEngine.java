package com.sunshine.orchestrator.peer;

import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.pipeline.MsgHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 受控 MsgHub 轮次 — 内部对话不上主 Timeline */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeerRoundEngine {

    private final ReActAgentFactory agentFactory;
    private final PromptComposer promptComposer;

    public PeerRunResult run(
            PeerTemplate template,
            String userQuery,
            String userId,
            String tenantId) {
        String runId = UUID.randomUUID().toString();
        List<PeerTranscriptEntry> transcript = new ArrayList<>();
        List<ReActAgent> peers = new ArrayList<>();
        ReActAgent moderator = null;
        java.util.Map<ReActAgent, PeerTemplate.PeerRole> peerRoleMap = new java.util.LinkedHashMap<>();
        for (PeerTemplate.PeerRole role : template.roles()) {
            ReActAgent agent = createAgent(runId, role, userId, tenantId);
            if (role.moderator()) {
                moderator = agent;
            } else {
                peers.add(agent);
                peerRoleMap.put(agent, role);
            }
        }
        if (peers.isEmpty() || moderator == null) {
            throw new IllegalStateException("peer template must have peers and moderator");
        }
        int maxRounds = template.maxRounds();
        try (MsgHub hub = MsgHub.builder()
                .name("peer-" + runId)
                .participants(peers.stream().map(AgentBase.class::cast).toList())
                .enableAutoBroadcast(true)
                .build()) {
            hub.enter().block();
            List<String> contextBlocks = new ArrayList<>();
            contextBlocks.add("用户问题：\n" + userQuery);
            for (int round = 1; round <= maxRounds; round++) {
                for (ReActAgent peer : peers) {
                    PeerTemplate.PeerRole role = peerRoleMap.get(peer);
                    String reply = invokeAgent(peer, userQuery, contextBlocks, role);
                    if (StringUtils.hasText(reply)) {
                        transcript.add(new PeerTranscriptEntry(
                                round, role.displayName(), role.skillId(), reply));
                        contextBlocks.add(PeerMsgSupport.formatTranscriptBlock(role.displayName(), reply));
                        hub.broadcast(Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name(role.displayName())
                                .content(List.of(TextBlock.builder().text(reply).build()))
                                .build()).block();
                    }
                }
            }
            hub.exit().block();
        } catch (Exception e) {
            log.warn("[PeerRoundEngine] MsgHub 执行异常 runId={}: {}", runId, e.getMessage());
            throw e;
        }
        return new PeerRunResult(runId, transcript, moderator, template);
    }

    private String invokeAgent(
            ReActAgent agent,
            String userQuery,
            List<String> contextBlocks,
            PeerTemplate.PeerRole role) {
        List<Msg> inputs = promptComposer.composeReactInputs(
                PromptComposeRequest.forReact(MemoryContext.forSubAgent(), userQuery, role.skillId(), contextBlocks, false));
        Msg result = agent.call(inputs).block();
        return PeerMsgSupport.extractText(result);
    }

    private ReActAgent createAgent(
            String runId,
            PeerTemplate.PeerRole role,
            String userId,
            String tenantId) {
        AgentRunRequest request = new AgentRunRequest(
                AgentRole.SUB,
                runId + "-" + role.skillId(),
                runId,
                MemoryContext.forSubAgent(),
                "",
                List.of(),
                userId,
                tenantId,
                null,
                role.skillId(),
                null,
                role.systemOverlay(),
                2,
                TimelineBinding.SUB_COMPRESSED,
                false);
        return agentFactory.create(request);
    }

    public record PeerRunResult(
            String runId,
            List<PeerTranscriptEntry> transcript,
            ReActAgent moderator,
            PeerTemplate template) {
    }
}
