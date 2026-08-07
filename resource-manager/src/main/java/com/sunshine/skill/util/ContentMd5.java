package com.sunshine.skill.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** 文件内容 MD5 — 二进制 diff 展示 */
public final class ContentMd5 {

    private ContentMd5() {
    }

    public static String hex(byte[] data) {
        byte[] input = data != null ? data : new byte[0];
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    public static String hexFromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return hex(new byte[0]);
        }
        return hex(Base64.getDecoder().decode(base64));
    }

    public static String hexFromText(String text) {
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return hex(bytes);
    }
}
