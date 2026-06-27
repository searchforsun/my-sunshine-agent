package com.sunshine.desensitize.service;

import com.sunshine.desensitize.config.DesensitizeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordDesensitizerTest {

    private KeywordDesensitizer desensitizer;

    @BeforeEach
    void setUp() {
        DesensitizeProperties props = new DesensitizeProperties();
        DesensitizeProperties.KeywordRule rule = new DesensitizeProperties.KeywordRule();
        rule.setId("salary");
        rule.setKeywords(List.of("月薪", "年薪"));
        rule.setReplacement("***");
        props.setRules(List.of(rule));
        desensitizer = new KeywordDesensitizer(props);
        desensitizer.rebuildTrie(props.getRules());
    }

    @Test
    void masksConfiguredKeywords() {
        assertThat(desensitizer.scrub("员工月薪8000元")).isEqualTo("员工***8000元");
        assertThat(desensitizer.scrub("年薪20万")).isEqualTo("***20万");
    }

    @Test
    void leavesUnmatchedText() {
        assertThat(desensitizer.scrub("普通制度说明")).isEqualTo("普通制度说明");
    }
}
