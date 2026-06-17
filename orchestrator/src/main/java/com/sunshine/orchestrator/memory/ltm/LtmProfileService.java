package com.sunshine.orchestrator.memory.ltm;

import com.sunshine.orchestrator.memory.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * LTM — 用户画像与稳定偏好，对话初始化时精简注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LtmProfileService {

    private final UserMemoryProfileRepository profileRepo;
    private final MemoryProperties memoryProperties;

    @Transactional(readOnly = true)
    public Optional<String> buildSnippet(String userId, String tenantId) {
        if (!memoryProperties.isEnabled() || !memoryProperties.getLtm().isEnabled()) {
            return Optional.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        return profileRepo.findByUserIdAndTenantId(userId, tid)
                .map(this::formatSnippet)
                .filter(StringUtils::hasText);
    }

    @Transactional
    public UserMemoryProfileEntity ensureProfile(String userId, String tenantId) {
        String tid = tenantId != null ? tenantId : "default";
        return profileRepo.findByUserIdAndTenantId(userId, tid)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    UserMemoryProfileEntity entity = new UserMemoryProfileEntity();
                    entity.setId(newId());
                    entity.setUserId(userId);
                    entity.setTenantId(tid);
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    return profileRepo.save(entity);
                });
    }

    private String formatSnippet(UserMemoryProfileEntity profile) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(profile.getDepartment())) {
            parts.add("部门=" + profile.getDepartment().strip());
        }
        if (StringUtils.hasText(profile.getRoleLabel())) {
            parts.add("角色=" + profile.getRoleLabel().strip());
        }
        if (StringUtils.hasText(profile.getPreferences())) {
            parts.add("偏好=" + profile.getPreferences().strip());
        }
        if (StringUtils.hasText(profile.getStableFacts())) {
            parts.add("事实=" + profile.getStableFacts().strip());
        }
        if (StringUtils.hasText(profile.getPermissions())) {
            parts.add("权限=" + profile.getPermissions().strip());
        }
        if (parts.isEmpty()) {
            return "";
        }
        String body = String.join("; ", parts);
        int max = memoryProperties.getLtm().getMaxChars();
        if (body.length() > max) {
            body = body.substring(0, max) + "…";
        }
        return "[用户画像 · LTM] " + body;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
