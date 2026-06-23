package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.SkillBindingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析用户 @ 指定或强提示句式，锁定 skillId（P0 @，P1 hint-patterns） */
@Component
@RequiredArgsConstructor
public class SkillBindingParser {

    private static final Pattern AT_PATTERN = Pattern.compile(
            "^@([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);

    private final SkillCatalogService skillCatalogService;
    private final SkillBindingProperties properties;

    public SkillBindingOutcome parse(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return SkillBindingOutcome.none(userMessage != null ? userMessage : "");
        }
        String trimmed = userMessage.strip();
        Matcher at = AT_PATTERN.matcher(trimmed);
        if (at.matches()) {
            String token = at.group(1);
            String rest = at.group(2) != null ? at.group(2).strip() : "";
            return resolveAndBind(token, rest, SkillBindingSource.AT_MENTION);
        }
        for (String rawPattern : properties.getHintPatterns()) {
            if (!StringUtils.hasText(rawPattern)) {
                continue;
            }
            Matcher hint = Pattern.compile(rawPattern.strip(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(trimmed);
            if (hint.find()) {
                String skillToken = hint.group("skill");
                if (StringUtils.hasText(skillToken)) {
                    return resolveAndBind(skillToken.strip(), trimmed, SkillBindingSource.HINT_PATTERN);
                }
            }
        }
        return SkillBindingOutcome.none(trimmed);
    }

    private SkillBindingOutcome resolveAndBind(String token, String effectiveQuery, SkillBindingSource source) {
        Optional<String> skillId = resolveSkillId(token);
        if (skillId.isEmpty()) {
            return SkillBindingOutcome.unknown(token);
        }
        String query = StringUtils.hasText(effectiveQuery) ? effectiveQuery : "请处理";
        return SkillBindingOutcome.bound(skillId.get(), query, source);
    }

    private Optional<String> resolveSkillId(String token) {
        Optional<SkillCatalogIndexEntry> byId = skillCatalogService.findIndex(token);
        if (byId.isPresent() && byId.get().enabled()) {
            return Optional.of(byId.get().id());
        }
        for (SkillCatalogIndexEntry entry : skillCatalogService.indexEntries()) {
            if (!entry.enabled()) {
                continue;
            }
            if (token.equalsIgnoreCase(entry.id()) || token.equalsIgnoreCase(entry.displayName())) {
                return Optional.of(entry.id());
            }
        }
        return Optional.empty();
    }
}
