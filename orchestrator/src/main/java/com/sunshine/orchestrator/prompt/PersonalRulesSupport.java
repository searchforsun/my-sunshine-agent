package com.sunshine.orchestrator.prompt;

import org.springframework.util.StringUtils;

/** 用户个人规则（soul）包装 — 注入提示词前的统一分隔头；仅 trim + 长度防御，不改写内容 */
public final class PersonalRulesSupport {

    public static final int MAX_LENGTH = 4000;
    private static final String HEADER = "## 用户个人规则\n";

    private PersonalRulesSupport() {
    }

    /** 空/全空白 → null（不注入）；超 MAX_LENGTH 截断（防御，前端已限长） */
    public static String wrap(String personalRules) {
        if (!StringUtils.hasText(personalRules)) {
            return null;
        }
        String trimmed = personalRules.strip();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        return HEADER + trimmed;
    }
}
