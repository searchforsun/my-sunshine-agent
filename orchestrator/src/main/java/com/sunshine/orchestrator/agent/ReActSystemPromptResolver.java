package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
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
    private final ToolRetrievalService toolRetrievalService;
    private final AgentExecutionProperties executionProperties;

    public String resolve(AgentRunRequest request) {
        String base = catalogHolder.snapshot().entry("system-prompt")
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReActSystemPromptResolver] catalog missing id=system-prompt");
                    return "";
                });
        // 5.5 retrieval 分层注入：MAIN 主链 Tier 0 全量工具名目录进稳定前缀（模板在 Catalog context.tool-directory）
        if (request != null && request.role() == AgentRole.MAIN && toolRetrievalService.retrievalEnabled()) {
            base = appendToolDirectory(base, request);
        }
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

    private String appendToolDirectory(String base, AgentRunRequest request) {
        String template = catalogHolder.snapshot().entry("context.tool-directory")
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReActSystemPromptResolver] catalog missing id=context.tool-directory");
                    return "";
                });
        if (!StringUtils.hasText(template)) {
            return base;
        }
        String tools = toolRetrievalService.renderToolDirectory(request.tenantId(), request.conversationKind());
        if (!StringUtils.hasText(tools)) {
            return base;
        }
        String block = template.replace("{tools}", tools);
        return base.isBlank() ? block : base + "\n\n" + block;
    }
}
