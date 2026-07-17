package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.SandboxPolicy;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.client.SkillCatalogClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.client.sandbox.CreateSessionRequest;
import com.sunshine.orchestrator.client.sandbox.SandboxPolicyDto;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话级沙箱（方案 B）— MAIN 工具常驻；首次 sandbox__* / 抽屉 list 时懒 create；
 * Skill 仅可选懒挂载 /skills/{id}/，不门控开箱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxSessionLifecycle {

    private final SkillCatalogClient skillCatalogClient;
    private final SandboxClient sandboxClient;
    private final ConversationSandboxStore conversationSandboxStore;
    private final AgentSandboxProperties sandboxProperties;

    /** run 级上下文 — 供工具线程 ensure（与 Holder 同：跨线程） */
    private final ConcurrentHashMap<String, RunContext> runContexts = new ConcurrentHashMap<>();

    /**
     * MAIN / SUB 开跑登记上下文（不创建 Docker）。PLANNER 忽略。
     */
    public void prepareRun(AgentRunRequest req) {
        if (req == null || (req.role() != AgentRole.MAIN && req.role() != AgentRole.SUB)) {
            return;
        }
        String bridgeId = req.resolveBridgeId();
        runContexts.put(bridgeId, RunContext.from(req));
        log.debug("[SandboxSession] prepareRun bridge={} role={} conv={} skill={}",
                bridgeId, req.role(), req.conversationId(), req.skillId());
    }

    /**
     * 工具调用前：确保会话已绑定到 bridge；可选挂载 skill。
     *
     * @return sessionId
     */
    public String ensureBound(String bridgeId) {
        if (!StringUtils.hasText(bridgeId)) {
            throw new IllegalStateException("sandbox ensure requires bridgeId");
        }
        String bid = bridgeId.strip();
        SandboxSessionHolder.Binding existing = SandboxSessionHolder.get(bid);
        RunContext ctx = runContexts.get(bid);
        if (existing != null && StringUtils.hasText(existing.sessionId())) {
            if (!sandboxClient.sessionRunning(existing.sessionId())
                    && sandboxClient.sessionAlive(existing.sessionId())) {
                sandboxClient.startSession(existing.sessionId());
            }
            if (ctx != null && StringUtils.hasText(ctx.skillId())) {
                maybeMountSkill(existing.sessionId(), ctx.tenantId(), ctx.conversationId(),
                        ctx.skillId(), ctx.userId());
            }
            return existing.sessionId();
        }
        if (ctx == null) {
            throw new IllegalStateException("sandbox run context missing; call prepareRun first");
        }
        EnsureResult result = ensureSession(
                ctx.userId(), ctx.tenantId(), ctx.conversationId(), ctx.skillId(), ctx.runId());
        SandboxSessionHolder.bind(bid, result.sessionId(), sessionPolicy());
        emitSandboxSessionSse(bid, ctx, result.loadedSkillIds());
        log.info("[SandboxSession] ensureBound session={} loaded={} bridge={} conv={}",
                result.sessionId(), result.loadedSkillIds(), bid, ctx.conversationId());
        return result.sessionId();
    }

    /**
     * 抽屉 / API：无 run 上下文时按对话 ensure（懒 create）。
     */
    public String ensureConversationSession(
            String userId, String tenantId, String conversationId, String skillId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId required");
        }
        EnsureResult result = ensureSession(
                userId,
                StringUtils.hasText(tenantId) ? tenantId.strip() : "default",
                conversationId.strip(),
                skillId,
                "workspace-api");
        log.info("[SandboxSession] ensureConversation session={} conv={} skill={}",
                result.sessionId(), conversationId, skillId);
        return result.sessionId();
    }

    /** 当前对话已加载的 skillId 列表（供 SSE） */
    public List<String> loadedSkillIds(String tenantId, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        return conversationSandboxStore.find(tenantId, conversationId)
                .map(ConversationSandboxBinding::loadedSkillIds)
                .orElse(List.of());
    }

    /** run 结束仅解绑 bridge + 清 run 上下文；对话级会话由 TTL / 删会话销毁 */
    public void closeQuietly(AgentRunRequest req) {
        if (req == null) {
            return;
        }
        String bridgeId = req.resolveBridgeId();
        runContexts.remove(bridgeId);
        String sid = SandboxSessionHolder.unbind(bridgeId);
        if (sid != null) {
            log.info("[SandboxSession] unbound bridge={} session={} (conversation-scoped keep)",
                    bridgeId, sid);
        }
    }

    /** 删除对话或 Reaper：关闭容器并清 Redis */
    public void destroyConversationSession(String tenantId, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        conversationSandboxStore.remove(tenantId, conversationId).ifPresent(b -> {
            sandboxClient.closeSession(b.sessionId());
            log.info("[SandboxSession] destroyed session={} conv={}", b.sessionId(), conversationId);
        });
    }

    private EnsureResult ensureSession(
            String userId,
            String tenantId,
            String conversationId,
            String skillId,
            String runId) {
        SandboxPolicy policy = sessionPolicy();
        String tid = StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
        if (!StringUtils.hasText(conversationId)) {
            String sid = createEmpty(userId, tid, skillId, runId, policy);
            List<String> loaded = List.of();
            if (StringUtils.hasText(skillId)) {
                mountSkill(sid, skillId.strip());
                loaded = List.of(skillId.strip());
            }
            return new EnsureResult(sid, loaded);
        }
        String conv = conversationId.strip();
        Optional<ConversationSandboxBinding> existing = conversationSandboxStore.find(tid, conv);
        if (existing.isPresent()) {
            ConversationSandboxBinding b = existing.get();
            if (sandboxClient.sessionAlive(b.sessionId())) {
                if (!sandboxClient.sessionRunning(b.sessionId())) {
                    sandboxClient.startSession(b.sessionId());
                }
                conversationSandboxStore.touch(tid, conv);
                List<String> loaded = maybeMountIntoBinding(b, skillId);
                return new EnsureResult(b.sessionId(), loaded);
            }
            sandboxClient.closeSession(b.sessionId());
            conversationSandboxStore.remove(tid, conv);
        }
        String sid = createEmpty(userId, tid, skillId, runId, policy);
        List<String> loaded = List.of();
        if (StringUtils.hasText(skillId)) {
            mountSkill(sid, skillId.strip());
            loaded = List.of(skillId.strip());
        }
        conversationSandboxStore.save(new ConversationSandboxBinding(
                sid, loaded, userId, tid, conv,
                ConversationSandboxBinding.STATE_RUNNING, null));
        return new EnsureResult(sid, loaded);
    }

    private List<String> maybeMountIntoBinding(ConversationSandboxBinding b, String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return b.loadedSkillIds();
        }
        String id = skillId.strip();
        List<String> loaded = new ArrayList<>(b.loadedSkillIds());
        if (loaded.contains(id)) {
            return List.copyOf(loaded);
        }
        mountSkill(b.sessionId(), id);
        loaded.add(id);
        conversationSandboxStore.save(b.withSkills(loaded).withState(ConversationSandboxBinding.STATE_RUNNING));
        return List.copyOf(loaded);
    }

    private void maybeMountSkill(
            String sessionId, String tenantId, String conversationId, String skillId, String userId) {
        if (!StringUtils.hasText(skillId) || !StringUtils.hasText(conversationId)) {
            return;
        }
        Optional<ConversationSandboxBinding> existing =
                conversationSandboxStore.find(tenantId, conversationId);
        if (existing.isEmpty()) {
            return;
        }
        maybeMountIntoBinding(existing.get(), skillId);
    }

    private String createEmpty(
            String userId, String tenantId, String skillId, String runId, SandboxPolicy policy) {
        return sandboxClient.createSession(new CreateSessionRequest(
                userId,
                tenantId,
                skillId,
                runId,
                toDto(policy),
                Map.of(),
                Map.of()));
    }

    private void mountSkill(String sessionId, String skillId) {
        Map<String, String> files = skillCatalogClient.fetchMaterial(skillId);
        sandboxClient.mountSkill(sessionId, skillId, files != null ? files : Map.of());
    }

    private void emitSandboxSessionSse(String bridgeId, RunContext ctx, List<String> loaded) {
        if (!StringUtils.hasText(ctx.conversationId())) {
            return;
        }
        StepEventBridge.offerStreamToken(bridgeId, StreamToken.sandboxSession(
                ctx.conversationId().strip(),
                StringUtils.hasText(ctx.skillId()) ? ctx.skillId().strip() : null,
                loaded));
    }

    private SandboxPolicy sessionPolicy() {
        AgentSandboxProperties.Runtime rt = sandboxProperties.getRuntime();
        if (rt == null) {
            rt = new AgentSandboxProperties.Runtime();
        }
        return new SandboxPolicy(
                rt.getRuntimeType() != null ? rt.getRuntimeType() : "docker",
                rt.getImage(),
                rt.getTimeoutSec(),
                rt.getMemoryMb(),
                rt.getCpus(),
                rt.getNetworkAllow() != null ? rt.getNetworkAllow() : List.of(),
                rt.getExecReadonlyAllow() != null ? rt.getExecReadonlyAllow() : List.of());
    }

    private static SandboxPolicyDto toDto(SandboxPolicy policy) {
        if (policy == null) {
            return null;
        }
        return new SandboxPolicyDto(
                policy.runtime(),
                policy.image(),
                policy.timeoutSec(),
                policy.memoryMb(),
                policy.cpus(),
                policy.networkAllow(),
                policy.execReadonlyAllow());
    }

    private record EnsureResult(String sessionId, List<String> loadedSkillIds) {}

    record RunContext(
            String userId,
            String tenantId,
            String conversationId,
            String skillId,
            String runId,
            String assistantMessageId) {
        static RunContext from(AgentRunRequest req) {
            return new RunContext(
                    req.userId(),
                    StringUtils.hasText(req.tenantId()) ? req.tenantId().strip() : "default",
                    req.conversationId(),
                    req.skillId(),
                    req.runId(),
                    req.assistantMessageId());
        }
    }
}
