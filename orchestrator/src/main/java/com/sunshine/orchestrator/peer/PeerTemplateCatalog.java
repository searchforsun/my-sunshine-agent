package com.sunshine.orchestrator.peer;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** agent.peer.templates Catalog */
@Component
@RequiredArgsConstructor
public class PeerTemplateCatalog {

    private final PeerProperties peerProperties;

    public Optional<PeerTemplate> find(String templateId) {
        if (!StringUtils.hasText(templateId) || peerProperties.getTemplates() == null) {
            return Optional.empty();
        }
        PeerProperties.PeerTemplateSpec spec = peerProperties.getTemplates().get(templateId.strip());
        if (spec == null || spec.getRoles() == null || spec.getRoles().isEmpty()) {
            return Optional.empty();
        }
        List<PeerTemplate.PeerRole> roles = new ArrayList<>();
        int idx = 0;
        for (PeerProperties.PeerRoleSpec role : spec.getRoles()) {
            if (!StringUtils.hasText(role.getSkillId())) {
                continue;
            }
            boolean moderator = role.isModerator()
                    || idx == spec.getRoles().size() - 1 && roles.stream().noneMatch(PeerTemplate.PeerRole::moderator);
            roles.add(new PeerTemplate.PeerRole(
                    role.getSkillId().strip(),
                    StringUtils.hasText(role.getDisplayName()) ? role.getDisplayName().strip() : role.getSkillId(),
                    role.getSystemOverlay(),
                    moderator));
            idx++;
        }
        if (roles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PeerTemplate(
                templateId.strip(),
                StringUtils.hasText(spec.getDisplayName()) ? spec.getDisplayName().strip() : templateId,
                List.copyOf(roles),
                Math.max(1, peerProperties.getMaxRounds())));
    }

    public String defaultTemplateId() {
        return "compliance-cross-review";
    }

    public ExecutionPlan sanitize(ExecutionPlan plan) {
        if (plan == null || plan.mode() != ExecutionMode.PEER_COLLAB) {
            return plan;
        }
        String templateId = plan.params() != null
                ? plan.params().get(PeerCollaborationParams.TEMPLATE_ID)
                : null;
        if (!StringUtils.hasText(templateId)) {
            templateId = defaultTemplateId();
        }
        if (find(templateId).isPresent()) {
            return plan;
        }
        return ExecutionPlan.reactFallback("unknown peer template: " + templateId);
    }
}
