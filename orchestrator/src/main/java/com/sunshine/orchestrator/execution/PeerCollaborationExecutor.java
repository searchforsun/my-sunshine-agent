package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.peer.PeerCollaborationParams;
import com.sunshine.orchestrator.peer.PeerMsgSupport;
import com.sunshine.orchestrator.peer.PeerRoundEngine;
import com.sunshine.orchestrator.peer.PeerRunAuditService;
import com.sunshine.orchestrator.peer.PeerTemplate;
import com.sunshine.orchestrator.peer.PeerTemplateCatalog;
import com.sunshine.orchestrator.peer.PeerTimelineSupport;
import com.sunshine.orchestrator.peer.PeerTranscriptEntry;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 第五模式 — 受控 MsgHub 多专家协作 + 仲裁汇总 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeerCollaborationExecutor {

    private final PeerTemplateCatalog templateCatalog;
    private final PeerRoundEngine peerRoundEngine;
    private final PeerRunAuditService peerRunAuditService;
    private final ReactExecutor reactExecutor;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionPlan sanitized = templateCatalog.sanitize(ctx.plan());
        if (sanitized.mode() != ExecutionMode.PEER_COLLAB) {
            log.warn("[PeerCollaborationExecutor] 模板无效，降级 react");
            return reactExecutor.execute(ctx.withPlan(sanitized));
        }
        String templateId = sanitized.params() != null
                ? sanitized.params().get(PeerCollaborationParams.TEMPLATE_ID)
                : templateCatalog.defaultTemplateId();
        if (!StringUtils.hasText(templateId)) {
            templateId = templateCatalog.defaultTemplateId();
        }
        PeerTemplate template = templateCatalog.find(templateId).orElse(null);
        if (template == null) {
            log.warn("[PeerCollaborationExecutor] 未找到模板 {}，降级 react", templateId);
            return reactExecutor.execute(ctx.withPlan(
                    ExecutionPlan.reactFallback("peer template missing: " + templateId)));
        }
        return Flux.just(PeerTimelineSupport.running(template))
                .concatWith(Flux.defer(() -> runCollaboration(ctx, template)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<StreamToken> runCollaboration(ExecutionStreamContext ctx, PeerTemplate template) {
        try {
            PeerRoundEngine.PeerRunResult result = peerRoundEngine.run(
                    template, ctx.userContent(), ctx.userId(), ctx.tenantId());
            peerRunAuditService.persistFinal(
                    ctx.conversationId(),
                    ctx.assistantMsgId(),
                    ctx.userId(),
                    ctx.tenantId(),
                    result);
            List<String> injected = new ArrayList<>();
            for (PeerTranscriptEntry entry : result.transcript()) {
                injected.add(PeerMsgSupport.formatTranscriptBlock(entry.roleName(), entry.content()));
            }
            PeerTemplate.PeerRole moderator = template.moderatorRole();
            Map<String, String> params = new LinkedHashMap<>();
            params.put(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY, ctx.userContent());
            if (moderator != null && StringUtils.hasText(moderator.skillId())) {
                params.put(SkillBindingOutcome.PARAM_SKILL, moderator.skillId());
            }
            ExecutionPlan answerPlan = new ExecutionPlan(
                    ExecutionMode.REACT, null, params, "peer:moderator-answer");
            return Flux.concat(
                    Flux.just(PeerTimelineSupport.complete(template, result.transcript())),
                    reactExecutor.executeWithInjected(ctx.withPlan(answerPlan), injected));
        } catch (Exception e) {
            log.warn("[PeerCollaborationExecutor] 协作失败，降级 react: {}", e.getMessage());
            return Flux.concat(
                    Flux.just(PeerTimelineSupport.complete(template, List.of())),
                    reactExecutor.execute(ctx.withPlan(ExecutionPlan.reactFallback("peer-collab failed"))));
        }
    }
}
