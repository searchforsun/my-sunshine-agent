package com.sunshine.desensitize.service;

import com.sunshine.desensitize.config.DesensitizeProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** AhoCorasick 关键词脱敏（Task 3.13） */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordDesensitizer {

    private final DesensitizeProperties properties;
    private volatile Trie trie = Trie.builder().build();
    private volatile Map<String, String> keywordReplacements = Map.of();

    @PostConstruct
    void rebuild() {
        rebuildTrie(properties.getRules());
    }

    /** 供单测 / 配置热更新后重建 */
    public void rebuildTrie(List<DesensitizeProperties.KeywordRule> rules) {
        Map<String, String> replacements = new HashMap<>();
        Trie.TrieBuilder builder = Trie.builder().ignoreOverlaps();
        if (rules != null) {
            for (DesensitizeProperties.KeywordRule rule : rules) {
                if (rule.getKeywords() == null) {
                    continue;
                }
                String repl = rule.getReplacement() != null ? rule.getReplacement() : "***";
                for (String keyword : rule.getKeywords()) {
                    if (keyword == null || keyword.isBlank()) {
                        continue;
                    }
                    String kw = keyword.strip();
                    replacements.put(kw, repl);
                    builder.addKeyword(kw);
                }
            }
        }
        this.keywordReplacements = Map.copyOf(replacements);
        this.trie = builder.build();
        log.info("[Desensitize] AhoCorasick 规则加载: keywords={}", replacements.size());
    }

    public String scrub(String text) {
        if (text == null || text.isEmpty() || keywordReplacements.isEmpty()) {
            return text;
        }
        List<Emit> emits = new ArrayList<>(trie.parseText(text));
        if (emits.isEmpty()) {
            return text;
        }
        emits.sort(Comparator.comparingInt(Emit::getStart).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (Emit emit : emits) {
            String repl = keywordReplacements.getOrDefault(emit.getKeyword(), "***");
            sb.replace(emit.getStart(), emit.getEnd() + 1, repl);
        }
        return sb.toString();
    }
}
