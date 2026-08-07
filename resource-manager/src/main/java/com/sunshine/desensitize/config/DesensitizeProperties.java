package com.sunshine.desensitize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 脱敏规则 SSOT（Nacos desensitize.rules） */
@Data
@ConfigurationProperties(prefix = "desensitize")
public class DesensitizeProperties {

    private boolean regexEnabled = true;
    private List<KeywordRule> rules = defaultRules();

    @Data
    public static class KeywordRule {
        private String id;
        private List<String> keywords = new ArrayList<>();
        /** 命中关键词后的替换文本 */
        private String replacement = "***";
    }

    private static List<KeywordRule> defaultRules() {
        KeywordRule salary = new KeywordRule();
        salary.setId("salary");
        salary.setKeywords(List.of("月薪", "年薪", "工资总额"));
        salary.setReplacement("***");
        KeywordRule bank = new KeywordRule();
        bank.setId("bank-account");
        bank.setKeywords(List.of("银行卡号", "银行账号"));
        bank.setReplacement("[账号已脱敏]");
        return List.of(salary, bank);
    }
}
