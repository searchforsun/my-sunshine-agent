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

/** 解析用户 @ 指定或强提示句式，锁定 skillId（P0 @，P1 hint-patterns，客户端 skillId） */
@Component
@RequiredArgsConstructor
public class SkillBindingParser {

    private static final Pattern AT_PATTERN = Pattern.compile(
            "^@([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
    /** 行内 @skill，token 后须为空白、标点或串尾 */
    private static final Pattern INLINE_AT_PATTERN = Pattern.compile(
            "@([\\w\\u4e00-\\u9fff-]+)(?=[\\s，。！？,.!?;；：:]|$)");

    private final SkillCatalogService skillCatalogService;
    private final SkillBindingProperties properties;

    public SkillBindingOutcome parse(String userMessage) {
        return parse(userMessage, null);
    }

    public SkillBindingOutcome parse(String userMessage, String clientSkillId) {
        if (!StringUtils.hasText(userMessage)) {
            return SkillBindingOutcome.none(userMessage != null ? userMessage : "");
        }
        String trimmed = userMessage.strip();
        if (StringUtils.hasText(clientSkillId)) {
            Optional<String> skillId = resolveSkillId(clientSkillId.strip());
            if (skillId.isEmpty()) {
                return SkillBindingOutcome.unknown(clientSkillId.strip());
            }
            return SkillBindingOutcome.bound(skillId.get(), stripSkillMentions(trimmed), SkillBindingSource.CLIENT);
        }
        Matcher at = AT_PATTERN.matcher(trimmed);
        if (at.matches()) {
            String token = at.group(1);
            String rest = at.group(2) != null ? at.group(2).strip() : "";
            return resolveAndBind(token, rest, SkillBindingSource.AT_MENTION);
        }
        Matcher inline = INLINE_AT_PATTERN.matcher(trimmed);
        while (inline.find()) {
            Optional<String> skillId = resolveSkillId(inline.group(1));
            if (skillId.isPresent()) {
                return SkillBindingOutcome.bound(
                        skillId.get(), stripSkillMentions(trimmed), SkillBindingSource.AT_MENTION);
            }
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

    /** 简单对话 / 工作流等禁用 Skill 时：去掉全部 @skill，按正文普通提问 */
    public String stripAtMention(String userMessage) {
        return stripSkillMentions(userMessage);
    }

    /** 去掉正文中全部 @skill token，折叠空白；空则返回「请处理」 */
    public String stripSkillMentions(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return userMessage != null ? userMessage : "";
        }
        String stripped = INLINE_AT_PATTERN.matcher(userMessage.strip()).replaceAll("");
        stripped = stripped.replaceAll("\\s{2,}", " ").strip();
        return StringUtils.hasText(stripped) ? stripped : "请处理";
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
