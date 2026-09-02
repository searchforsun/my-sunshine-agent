package com.sunshine.common.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 模型 API Key AES/GCM 加解密（SSOT）。格式：Base64(IV||ciphertextWithTag)。
 * 由 {@link ModelCryptoAutoConfiguration} 注册为 Bean；禁止各服务再拷贝实现。
 */
public class ModelCryptoService {

    public static final String UNSET = "UNSET";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String MASKED = "sk-****";

    private final byte[] aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ModelCryptoService(String aesKeyMaterial) {
        this.aesKey = deriveKey(aesKeyMaterial);
    }

    public boolean isConfigured(String enc) {
        return enc != null && !enc.isBlank() && !UNSET.equals(enc.strip());
    }

    /** 管理面脱敏：已配置返回 sk-****，未配置返回 null */
    public String maskForAdmin(String enc) {
        return isConfigured(enc) ? MASKED : null;
    }

    /** 明文空白或 UNSET → 存 UNSET；禁止把占位符当真实密钥加密 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || UNSET.equals(plaintext.strip())) {
            return UNSET;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.strip().getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("AES encrypt failed", e);
        }
    }

    /** 管理面：未配置返回空串；密文损坏抛 IllegalStateException */
    public String decrypt(String enc) {
        if (!isConfigured(enc)) {
            return "";
        }
        return doDecrypt(enc.strip());
    }

    /** 网关调用上游：未配置明文密钥则失败，禁止静默空串 */
    public String requireDecrypt(String enc) {
        if (!isConfigured(enc)) {
            throw new IllegalStateException(
                    "provider api key is UNSET; configure key in model admin before calling upstream");
        }
        return doDecrypt(enc.strip());
    }

    private String doDecrypt(String enc) {
        try {
            byte[] combined = Base64.getDecoder().decode(enc);
            if (combined.length <= GCM_IV_LENGTH) {
                throw new IllegalStateException("ciphertext too short");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES decrypt failed", e);
        }
    }

    /** Base64 16/24/32 字节原样；否则 SHA-256(utf8) 派生 32 字节 */
    public static byte[] deriveKey(String material) {
        if (material == null || material.isBlank()) {
            throw new IllegalStateException("model.crypto.aes-key is required");
        }
        String trimmed = material.strip();
        byte[] candidate;
        try {
            candidate = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException ex) {
            candidate = trimmed.getBytes(StandardCharsets.UTF_8);
        }
        if (candidate.length == 16 || candidate.length == 24 || candidate.length == 32) {
            return candidate;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(trimmed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive model.crypto.aes-key", e);
        }
    }
}
