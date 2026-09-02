package com.sunshine.tool.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class SchemaHashUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaHashUtil() {
    }

    public static String hash(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return hashString("{}");
        }
        try {
            return hashString(MAPPER.writeValueAsString(schema));
        } catch (JsonProcessingException e) {
            return hashString(schema.toString());
        }
    }

    private static String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
