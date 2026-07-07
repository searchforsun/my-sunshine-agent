package com.sunshine.orchestrator.peer;

import java.util.List;

/** 运行时 peer 模板视图 */
public record PeerTemplate(
        String id,
        String displayName,
        List<PeerRole> roles,
        int maxRounds) {

    public record PeerRole(
            String skillId,
            String displayName,
            String systemOverlay,
            boolean moderator) {
    }

    public List<PeerRole> peerRoles() {
        return roles.stream().filter(r -> !r.moderator()).toList();
    }

    public PeerRole moderatorRole() {
        return roles.stream().filter(PeerRole::moderator).findFirst()
                .orElse(roles.isEmpty() ? null : roles.get(roles.size() - 1));
    }
}
