package com.sunshine.orchestrator.grounding;

import com.sunshine.orchestrator.config.AgentGroundingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业数据 Grounding 校验 — 无 tool/rag/上游证据时拦截含金额或制度名的表述（Task 3.7）
 */
@Component
@RequiredArgsConstructor
public class AnswerGroundingChecker {

    /** 金额：¥/￥ 或「数字+元/万元/亿元」 */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:¥|￥)\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?\\s*(?:万|亿)?\\s*元");
    /** 制度名：《标题》或「专名+管理制度/办法…」（避免「查询制度」误报） */
    private static final Pattern POLICY = Pattern.compile(
            "《[^《》]{2,40}》|[\\u4e00-\\u9fa5]{2,8}(?:管理制度|管理办法|实施细则|管理条例|政策规定|规章制度)");

    private final AgentGroundingProperties properties;

    public GroundingVerdict check(String answer, GroundingEvidence evidence) {
        if (!properties.isEnabled()) {
            return GroundingVerdict.pass();
        }
        if (!StringUtils.hasText(answer)) {
            return GroundingVerdict.pass();
        }
        List<String> triggers = detectTriggers(answer);
        if (triggers.isEmpty()) {
            return GroundingVerdict.pass();
        }
        if (evidence != null && evidence.hasToolOrRag()) {
            return GroundingVerdict.pass();
        }
        String reason = properties.getRejectionMessage();
        if (!StringUtils.hasText(reason)) {
            reason = "答复包含未经验证的企业数据表述。";
        }
        return GroundingVerdict.fail(reason.strip(), triggers);
    }

    static List<String> detectTriggers(String answer) {
        List<String> triggers = new ArrayList<>();
        collectMatches(AMOUNT, answer, triggers);
        collectMatches(POLICY, answer, triggers);
        return triggers;
    }

    private static void collectMatches(Pattern pattern, String text, List<String> triggers) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String hit = matcher.group().strip();
            if (!hit.isEmpty() && !triggers.contains(hit)) {
                triggers.add(hit);
            }
        }
    }
}
