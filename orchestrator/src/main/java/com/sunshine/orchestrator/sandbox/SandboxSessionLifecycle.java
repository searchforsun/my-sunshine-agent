package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.SandboxPolicy;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.SandboxClient;
import com.sunshine.orchestrator.client.SkillCatalogClient;
import com.sunshine.orchestrator.client.sandbox.CreateSessionRequest;
import com.sunshine.orchestrator.client.sandbox.SandboxPolicyDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Agent run 沙箱会话生命周期 — open 于 run 开头，close 于 doFinally（取消/错误亦关闭）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxSessionLifecycle {

    private final SkillCatalogService skillCatalogService;
    private final SkillCatalogClient skillCatalogClient;
    private final SandboxClient sandboxClient;

    public void openIfNeeded(AgentRunRequest req) {
        if (req == null || !needsSandbox(req.skillId())) {
            return;
        }
        String skillId = req.skillId().strip();
        SkillCatalogEntry detail = skillCatalogService.find(skillId)
                .orElseThrow(() -> new IllegalStateException("sandbox skill not in catalog: " + skillId));
        Map<String, String> files = skillCatalogClient.fetchMaterial(skillId);
        SandboxPolicy policy = detail.sandboxPolicy();
        String sid = sandboxClient.createSession(new CreateSessionRequest(
                req.userId(),
                req.tenantId(),
                skillId,
                req.runId(),
                toDto(policy),
                files != null ? files : Map.of(),
                Map.of()));
        SandboxSessionHolder.bind(sid, policy);
        log.info("[SandboxSession] opened session={} skill={} run={}", sid, skillId, req.runId());
    }

    public void closeQuietly() {
        String sid = SandboxSessionHolder.unbind();
        if (sid != null) {
            sandboxClient.closeSession(sid);
            log.info("[SandboxSession] closed session={}", sid);
        }
    }

    boolean needsSandbox(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return false;
        }
        return skillCatalogService.find(skillId.strip())
                .map(e -> e.sandbox() != null && !"none".equalsIgnoreCase(e.sandbox().strip()))
                .orElse(false);
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
}
