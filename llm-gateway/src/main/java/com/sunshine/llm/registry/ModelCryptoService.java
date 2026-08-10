package com.sunshine.llm.registry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES/GCM 加解密，与 resource-manager 算法一致：Base64(IV||ciphertextWithTag)。
 */
@Component
public class ModelCryptoService {

    public static final String UNSET = "UNSET";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final byte[] aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ModelCryptoService(@Value("${model.crypto.aes-key:sunshine-model-aes-key-32b!!}") String aesKeyMaterial) {
        this.aesKey = deriveKey(aesKeyMaterial);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("AES encrypt failed", e);
        }
    }

    public String decrypt(String apiKeyEnc) {
        if (apiKeyEnc == null || apiKeyEnc.isBlank() || UNSET.equals(apiKeyEnc.strip())) {
            throw new IllegalStateException("provider api key is UNSET; configure key in model admin before calling upstream");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(apiKeyEnc.strip());
            if (decoded.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
            System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES decrypt failed", e);
        }
    }

    public boolean isConfigured(String apiKeyEnc) {
        return apiKeyEnc != null && !apiKeyEnc.isBlank() && !UNSET.equals(apiKeyEnc.strip());
    }

    /** 与 resource-manager ModelCryptoService.resolveKeyBytes 保持一致。 */
    static byte[] deriveKey(String material) {
        if (material == null || material.isBlank()) {
            throw new IllegalArgumentException("model.crypto.aes-key must not be blank");
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
            throw new IllegalStateException("derive AES key failed", e);
        }
    }
}
