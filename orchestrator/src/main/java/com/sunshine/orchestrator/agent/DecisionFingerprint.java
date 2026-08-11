package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** request_decision 预决策指纹：title + 规范化 questions（续跑与 Tool 入口共用） */
public final class DecisionFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionFingerprint() {
    }

    public static String of(String title, List<DecisionQuestion> questions) {
        String raw = nullToEmpty(title) + "\n" + canonicalQuestionsJson(questions);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    static String canonicalQuestionsJson(List<DecisionQuestion> questions) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (questions != null) {
            for (DecisionQuestion question : questions) {
                if (question == null) {
                    continue;
                }
                ObjectNode node = MAPPER.createObjectNode();
                node.put("id", nullToEmpty(question.id()));
                node.put("prompt", nullToEmpty(question.prompt()));
                node.put("allowMultiple", question.allowMultiple());
                ArrayNode optionsArr = MAPPER.createArrayNode();
                if (question.options() != null) {
                    for (DecisionOption opt : question.options()) {
                        if (opt == null) {
                            continue;
                        }
                        ObjectNode optNode = MAPPER.createObjectNode();
                        optNode.put("id", nullToEmpty(opt.id()));
                        optNode.put("label", nullToEmpty(opt.label()));
                        optionsArr.add(optNode);
                    }
                }
                node.set("options", optionsArr);
                arr.add(node);
            }
        }
        return arr.toString();
    }

    private static String nullToEmpty(String text) {
        return text != null ? text : "";
    }
}
