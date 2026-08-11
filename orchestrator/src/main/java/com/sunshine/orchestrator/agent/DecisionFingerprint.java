package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** request_decision 预决策指纹：question + 规范化 options（续跑与 Tool 入口共用） */
public final class DecisionFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionFingerprint() {
    }

    public static String of(String question, List<DecisionOption> options) {
        return of(question, canonicalOptionsJson(options));
    }

    public static String of(String question, String optionsJson) {
        String raw = nullToEmpty(question) + "\n" + nullToEmpty(optionsJson);
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

    static String canonicalOptionsJson(List<DecisionOption> options) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (options != null) {
            for (DecisionOption opt : options) {
                if (opt == null) {
                    continue;
                }
                ObjectNode node = MAPPER.createObjectNode();
                node.put("value", nullToEmpty(opt.value()));
                node.put("label", nullToEmpty(opt.label()));
                if (opt.description() != null) {
                    node.put("description", opt.description());
                } else {
                    node.putNull("description");
                }
                node.put("requireInput", opt.requireInput());
                arr.add(node);
            }
        }
        return arr.toString();
    }

    private static String nullToEmpty(String text) {
        return text != null ? text : "";
    }
}
