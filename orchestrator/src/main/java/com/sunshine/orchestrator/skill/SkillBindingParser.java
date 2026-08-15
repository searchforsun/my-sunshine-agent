package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ResourceKindFilter;
import com.sunshine.orchestrator.config.SkillBindingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析用户 / 指定或强提示句式，锁定 skillId（P0 /，P1 hint-patterns，客户端 skillId）。
 * 前缀与前端 Composer 对齐：/ 触发 Skill 补全；@ 为文件夹路径补全，不参与绑定。
 * soft binding：/xxx 未命中注册 skill 时视为普通内容，保留原文走意图识别；
 * 命中时正文保留完整原文（含 /skill token）。
 */
@Component
@RequiredArgsConstructor
public class SkillBindingParser {
    private static final Pattern SLASH_PATTERN = Pattern.compile(
            "^/([\\w\\u4e00-\\u9fff-]+)(?:\\s+(.*)|\\s*)$", Pattern.DOTALL);
    /** 行内 /skill，token 后须为空白、标点或串尾 */
    private static final Pattern INLINE_SLASH_PATTERN = Pattern.compile(
            "/([\\w\\u4e00-\\u9fff-]+)(?=[\\s，。！？,.!?;；：:]|$)");

    private final SkillCatalogService skillCatalogService;
    private final SkillBindingProperties properties;

    public SkillBindingOutcome parse(String userMessage) {
        return parse(userMessage, null, null);
    }

    public SkillBindingOutcome parse(String userMessage, String clientSkillId) {
        return parse(userMessage, clientSkillId, null);
    }

    public SkillBindingOutcome parse(String userMessage, String clientSkillId, String sessionKind) {
        if (!StringUtils.hasText(userMessage)) {
            return SkillBindingOutcome.none(userMessage != null ? userMessage : "");
        }
        String trimmed = userMessage.strip();
        if (StringUtils.hasText(clientSkillId)) {
            Optional<String> skillId = resolveSkillId(clientSkillId.strip(), sessionKind);
            if (skillId.isEmpty()) {
                return SkillBindingOutcome.unknown(clientSkillId.strip());
            }
            return SkillBindingOutcome.bound(skillId.get(), trimmed, SkillBindingSource.CLIENT);
        }
        Matcher at = SLASH_PATTERN.matcher(trimmed);
        if (at.matches()) {
            String token = at.group(1);
            return resolveAndBind(token, trimmed, SkillBindingSource.SLASH_MENTION, sessionKind);
        }
        Matcher inline = INLINE_SLASH_PATTERN.matcher(trimmed);
        while (inline.find()) {
            Optional<String> skillId = resolveSkillId(inline.group(1), sessionKind);
            if (skillId.isPresent()) {
                return SkillBindingOutcome.bound(skillId.get(), trimmed, SkillBindingSource.SLASH_MENTION);
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
                    return resolveAndBind(skillToken.strip(), trimmed, SkillBindingSource.HINT_PATTERN, sessionKind);
                }
            }
        }
        return SkillBindingOutcome.none(trimmed);
    }

    /** 简单对话 / 工作流等禁用 Skill 时：去掉全部 /skill，按正文普通提问 */
    public String stripSlashMention(String userMessage) {
        return stripSkillMentions(userMessage);
    }

    /** 去掉正文中全部 /skill token，折叠空白；空则返回「请处理」 */
    public String stripSkillMentions(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return userMessage != null ? userMessage : "";
        }
        String stripped = INLINE_SLASH_PATTERN.matcher(userMessage.strip()).replaceAll("");
        stripped = stripped.replaceAll("\\s{2,}", " ").strip();
        return StringUtils.hasText(stripped) ? stripped : "请处理";
    }

    private SkillBindingOutcome resolveAndBind(String token, String effectiveQuery, SkillBindingSource source,
            String sessionKind) {
        Optional<String> skillId = resolveSkillId(token, sessionKind);
        if (skillId.isEmpty()) {
            // soft binding：/xxx 未命中注册 skill 时视为普通内容，保留原文走意图识别
            return SkillBindingOutcome.none(effectiveQuery);
        }
        String query = StringUtils.hasText(effectiveQuery) ? effectiveQuery : "请处理";
        return SkillBindingOutcome.bound(skillId.get(), query, source);
    }

    /** 解析到 skill 后再按会话 kind 过滤（保留 all + 同 kind），缺省会话形态按 chat */
    private Optional<String> resolveSkillId(String token, String sessionKind) {
        Optional<SkillCatalogIndexEntry> byId = skillCatalogService.findIndex(token);
        if (byId.isPresent() && byId.get().enabled()
                && ResourceKindFilter.matches(byId.get().kind(), sessionKind)) {
            return Optional.of(byId.get().id());
        }
        for (SkillCatalogIndexEntry entry : skillCatalogService.indexEntries()) {
            if (!entry.enabled()) {
                continue;
            }
            if (token.equalsIgnoreCase(entry.id()) || token.equalsIgnoreCase(entry.displayName())) {
                if (ResourceKindFilter.matches(entry.kind(), sessionKind)) {
                    return Optional.of(entry.id());
                }
                break;
            }
        }
        return Optional.empty();
    }
}

