package com.sunshine.orchestrator.memory.ltm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 旧 LTM 表已 DROP；Task 8 前空实现，保证 MemoryLifecycle 等过渡路径可编译。
 */
@Slf4j
@Service
public class LtmProfileService {

    public Optional<String> buildSnippet(String userId, String tenantId) {
        return Optional.empty();
    }

    public void ensureProfile(String userId, String tenantId) {
        // no-op：user_memory_profile 已废止
    }
}
