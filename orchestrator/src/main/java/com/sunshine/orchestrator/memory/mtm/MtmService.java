package com.sunshine.orchestrator.memory.mtm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 旧 MTM 表已 DROP；Task 8 前空实现，保证过渡路径可编译。
 */
@Slf4j
@Service
public class MtmService {

    public Optional<String> recallSnippet(String userId, String tenantId, String query, String excludeConvId) {
        return Optional.empty();
    }

    public void saveSummary(
            String userId, String tenantId, String convId, String summary, String intent) {
        // no-op：conversation_memory_mtm 已废止
    }
}
