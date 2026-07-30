package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.catalog.AgentCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 Chat $agent 绑定 */
@Component
@RequiredArgsConstructor
public class AgentBindingParser {
    private static final Pattern DOLLAR_HEAD = Pattern.compile(
            "^\\$([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
    private static final Pattern INLINE_DOLLAR = Pattern.compile(
            "\\$([\\w\\u4e00-\\u9fff-]+)(?=[\\s，。！？,.!?;；：:]|$)");

    private final AgentCatalogService agentCatalogService;

    public AgentBindingOutcome parse(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return AgentBindingOutcome.none("");
        }
        String trimmed = userMessage.strip();
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        Matcher head = DOLLAR_HEAD.matcher(trimmed);
        if (head.matches()) {
            Optional<String> first = resolveAgentId(head.group(1));
            if (first.isEmpty()) {
                return AgentBindingOutcome.unknown(head.group(1));
            }
            agentIds.add(first.get());
            collectInline(head.group(2), agentIds);
            if (agentIds.isEmpty()) {
                return AgentBindingOutcome.none(trimmed);
            }
            return AgentBindingOutcome.bound(new ArrayList<>(agentIds), stripAgentMentions(trimmed));
        }
        collectInline(trimmed, agentIds);
        if (agentIds.isEmpty()) {
            return AgentBindingOutcome.none(trimmed);
        }
        return AgentBindingOutcome.bound(new ArrayList<>(agentIds), stripAgentMentions(trimmed));
    }

    public String stripAgentMentions(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return userMessage != null ? userMessage : "";
        }
        String stripped = userMessage.replaceAll("\\$[\\w\\u4e00-\\u9fff-]+", " ").replaceAll("\\s+", " ").strip();
        return stripped.isEmpty() ? "请处理" : stripped;
    }

    private void collectInline(String text, LinkedHashSet<String> agentIds) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher inline = INLINE_DOLLAR.matcher(text);
        while (inline.find()) {
            Optional<String> agentId = resolveAgentId(inline.group(1));
            if (agentId.isEmpty()) {
                throw new IllegalStateException("unknown agent: " + inline.group(1));
            }
            agentIds.add(agentId.get());
        }
    }

    private Optional<String> resolveAgentId(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String raw = token.strip();
        Optional<AgentCatalogIndexEntry> byId = agentCatalogService.findIndex(raw);
        if (byId.isPresent()) {
            return Optional.of(byId.get().id());
        }
        return agentCatalogService.indexEntries().stream()
                .filter(e -> raw.equals(e.displayName()))
                .map(AgentCatalogIndexEntry::id)
                .findFirst();
    }
}
