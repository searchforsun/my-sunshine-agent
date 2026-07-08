package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.catalog.ExpertCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.ExpertCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 Chat $expert 绑定 */
@Component
@RequiredArgsConstructor
public class ExpertBindingParser {
    private static final Pattern DOLLAR_HEAD = Pattern.compile(
            "^\\$([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
    private static final Pattern INLINE_DOLLAR = Pattern.compile(
            "\\$([\\w\\u4e00-\\u9fff-]+)(?=[\\s，。！？,.!?;；：:]|$)");

    private final ExpertCatalogService expertCatalogService;

    public ExpertBindingOutcome parse(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return ExpertBindingOutcome.none("");
        }
        String trimmed = userMessage.strip();
        LinkedHashSet<String> expertIds = new LinkedHashSet<>();
        Matcher head = DOLLAR_HEAD.matcher(trimmed);
        if (head.matches()) {
            Optional<String> first = resolveExpertId(head.group(1));
            if (first.isEmpty()) {
                return ExpertBindingOutcome.unknown(head.group(1));
            }
            expertIds.add(first.get());
            collectInline(head.group(2), expertIds);
            if (expertIds.isEmpty()) {
                return ExpertBindingOutcome.none(trimmed);
            }
            return ExpertBindingOutcome.bound(new ArrayList<>(expertIds), stripExpertMentions(trimmed));
        }
        collectInline(trimmed, expertIds);
        if (expertIds.isEmpty()) {
            return ExpertBindingOutcome.none(trimmed);
        }
        return ExpertBindingOutcome.bound(new ArrayList<>(expertIds), stripExpertMentions(trimmed));
    }

    public String stripExpertMentions(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return userMessage != null ? userMessage : "";
        }
        String stripped = userMessage.replaceAll("\\$[\\w\\u4e00-\\u9fff-]+", " ").replaceAll("\\s+", " ").strip();
        return stripped.isEmpty() ? "请处理" : stripped;
    }

    private void collectInline(String text, LinkedHashSet<String> expertIds) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher inline = INLINE_DOLLAR.matcher(text);
        while (inline.find()) {
            Optional<String> expertId = resolveExpertId(inline.group(1));
            if (expertId.isEmpty()) {
                throw new IllegalStateException("unknown expert: " + inline.group(1));
            }
            expertIds.add(expertId.get());
        }
    }

    private Optional<String> resolveExpertId(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String raw = token.strip();
        Optional<ExpertCatalogIndexEntry> byId = expertCatalogService.findIndex(raw);
        if (byId.isPresent()) {
            return Optional.of(byId.get().id());
        }
        return expertCatalogService.indexEntries().stream()
                .filter(e -> raw.equals(e.displayName()))
                .map(ExpertCatalogIndexEntry::id)
                .findFirst();
    }
}
