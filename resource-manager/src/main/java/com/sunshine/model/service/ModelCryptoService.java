package com.sunshine.model.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.model.exception.ModelErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ModelCryptoService {

    public static final String UNSET = "UNSET";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String MASKED = "sk-****";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ModelCryptoService(@Value("${model.crypto.aes-key:}") String aesKeyMaterial) {
        this.secretKey = new SecretKeySpec(resolveKeyBytes(aesKeyMaterial), "AES");
    }

    public boolean isConfigured(String enc) {
        return StringUtils.hasText(enc) && !UNSET.equals(enc.strip());
    }

    /** 管理面脱敏：已配置返回 sk-****，未配置返回 null */
    public String maskForAdmin(String enc) {
        return isConfigured(enc) ? MASKED : null;
    }

    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext) || UNSET.equals(plaintext.strip())) {
            return UNSET;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.strip().getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new BizException(ModelErrorCode.CRYPTO_FAILED);
        }
    }

    /** UNSET / 空密文返回空串；管理 API 禁止调用本方法回传明文 */
    public String decrypt(String enc) {
        if (!isConfigured(enc)) {
            return "";
        }
        try {
            byte[] combined = Base64.getDecoder().decode(enc.strip());
            if (combined.length <= GCM_IV_LENGTH) {
                throw new BizException(ModelErrorCode.CRYPTO_FAILED);
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ModelErrorCode.CRYPTO_FAILED);
        }
    }

    static byte[] resolveKeyBytes(String material) {
        if (!StringUtils.hasText(material)) {
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
