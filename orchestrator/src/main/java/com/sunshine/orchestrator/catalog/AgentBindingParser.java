package com.sunshine.orchestrator.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Chat $agent 绑定。
 * soft binding：$xxx 未命中注册 agent 时视为普通内容，保留原文走意图识别；
 * 命中时正文保留完整原文（含 $agent token）。
 */
@Component
@RequiredArgsConstructor
public class AgentBindingParser {
    private static final Pattern DOLLAR_HEAD = Pattern.compile(
            "^\\$([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
    private static final Pattern INLINE_DOLLAR = Pattern.compile(
            "\\$([\\w\\u4e00-\\u9fff-]+)(?=[\\s，。！？,.!?;；：:]|$)");

    private final AgentCatalogService agentCatalogService;

    public AgentBindingOutcome parse(String userMessage) {
        return parse(userMessage, null);
    }

    public AgentBindingOutcome parse(String userMessage, String sessionKind) {
        if (!StringUtils.hasText(userMessage)) {
            return AgentBindingOutcome.none("");
        }
        String trimmed = userMessage.strip();
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        Matcher head = DOLLAR_HEAD.matcher(trimmed);
        if (head.matches()) {
            resolveAgentId(head.group(1), sessionKind).ifPresent(agentIds::add);
            collectInline(head.group(2), agentIds, sessionKind);
            if (agentIds.isEmpty()) {
                // soft binding：$xxx 未命中注册 agent 时视为普通内容，保留原文走意图识别
                return AgentBindingOutcome.none(trimmed);
            }
            // keep raw：绑定 token 属用户内容，正文保留完整原文（含 $agent）
            return AgentBindingOutcome.bound(new ArrayList<>(agentIds), trimmed);
        }
        collectInline(trimmed, agentIds, sessionKind);
        if (agentIds.isEmpty()) {
            return AgentBindingOutcome.none(trimmed);
        }
        return AgentBindingOutcome.bound(new ArrayList<>(agentIds), trimmed);
    }

    /** workflow 钉死等禁用绑定场景：剥离全部 $agent token 后按普通正文提问；空则保留原文，不更改用户输入 */
    public String stripAgentMentions(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return userMessage != null ? userMessage : "";
        }
        String stripped = userMessage.replaceAll("\\$[\\w\\u4e00-\\u9fff-]+", " ").replaceAll("\\s+", " ").strip();
        return stripped.isEmpty() ? userMessage.strip() : stripped;
    }

    private void collectInline(String text, LinkedHashSet<String> agentIds, String sessionKind) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher inline = INLINE_DOLLAR.matcher(text);
        while (inline.find()) {
            resolveAgentId(inline.group(1), sessionKind).ifPresent(agentIds::add);
        }
    }

    /** 解析到 agent 后再按会话 kind 过滤（保留 all + 同 kind），缺省会话形态按 chat */
    private Optional<String> resolveAgentId(String token, String sessionKind) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String raw = token.strip();
        Optional<AgentCatalogIndexEntry> byId = agentCatalogService.findIndex(raw);
        if (byId.isPresent()) {
            return ResourceKindFilter.matches(byId.get().kind(), sessionKind)
                    ? Optional.of(byId.get().id())
                    : Optional.empty();
        }
        return agentCatalogService.indexEntries().stream()
                .filter(e -> raw.equals(e.displayName()))
                .filter(e -> ResourceKindFilter.matches(e.kind(), sessionKind))
                .map(AgentCatalogIndexEntry::id)
                .findFirst();
    }
}
