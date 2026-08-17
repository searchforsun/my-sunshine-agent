package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** base-system 解析 SSOT：factory 构建 agent 与 runtime 分组估算共用，避免双份逻辑漂移 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActSystemPromptResolver {

    private final PromptCatalogHolder catalogHolder;

    public String resolve(AgentRunRequest request) {
        String base = catalogHolder.snapshot().entry("system-prompt")
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReActSystemPromptResolver] catalog missing id=system-prompt");
                    return "";
                });
        String overlay = request != null ? request.systemOverlay() : null;
        if (!StringUtils.hasText(overlay)) {
            return base;
        }
        String trimmed = overlay.strip();
        if (base.isBlank()) {
            return trimmed;
        }
        return base + "\n\n" + trimmed;
    }
}
