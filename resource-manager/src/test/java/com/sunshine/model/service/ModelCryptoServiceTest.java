package com.sunshine.model.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCryptoServiceTest {

    @Test
    void encryptDecryptRoundtrip_with32ByteUtf8Key() {
        ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");
        String enc = crypto.encrypt("sk-test-secret-key");
        assertThat(enc).isNotBlank().isNotEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.isConfigured(enc)).isTrue();
        assertThat(crypto.decrypt(enc)).isEqualTo("sk-test-secret-key");
        assertThat(crypto.maskForAdmin(enc)).isEqualTo("sk-****");
    }

    @Test
    void unsetSentinel_encryptEmptyAndNull() {
        ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");
        assertThat(crypto.encrypt(null)).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.encrypt("")).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.encrypt("   ")).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.encrypt(ModelCryptoService.UNSET)).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.isConfigured(ModelCryptoService.UNSET)).isFalse();
        assertThat(crypto.isConfigured(null)).isFalse();
        assertThat(crypto.decrypt(ModelCryptoService.UNSET)).isEmpty();
        assertThat(crypto.maskForAdmin(ModelCryptoService.UNSET)).isNull();
    }

    @Test
    void resolveKey_derivesSha256WhenLengthInvalid() {
        byte[] derived = ModelCryptoService.resolveKeyBytes("short-key");
        assertThat(derived).hasSize(32);
        ModelCryptoService crypto = new ModelCryptoService("short-key");
        String enc = crypto.encrypt("plain");
        assertThat(crypto.decrypt(enc)).isEqualTo("plain");
    }

    @Test
    void resolveKey_acceptsBase64Aes256() {
        byte[] raw = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(raw);
        assertThat(ModelCryptoService.resolveKeyBytes(b64)).isEqualTo(raw);
    }
}
