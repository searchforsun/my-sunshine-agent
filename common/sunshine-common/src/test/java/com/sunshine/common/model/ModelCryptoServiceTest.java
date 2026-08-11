package com.sunshine.common.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCryptoServiceTest {

    private final ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");

    @Test
    void encryptDecryptRoundtrip_with32ByteUtf8Key() {
        String enc = crypto.encrypt("sk-test-secret-key");
        assertThat(enc).isNotBlank().isNotEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.isConfigured(enc)).isTrue();
        assertThat(crypto.decrypt(enc)).isEqualTo("sk-test-secret-key");
        assertThat(crypto.requireDecrypt(enc)).isEqualTo("sk-test-secret-key");
        assertThat(crypto.maskForAdmin(enc)).isEqualTo("sk-****");
    }

    @Test
    void unsetSentinel_encryptEmptyAndNull() {
        assertThat(crypto.encrypt(null)).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.encrypt("")).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.encrypt("   ")).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.encrypt(ModelCryptoService.UNSET)).isEqualTo(ModelCryptoService.UNSET);
        assertThat(crypto.isConfigured(ModelCryptoService.UNSET)).isFalse();
        assertThat(crypto.isConfigured(null)).isFalse();
        assertThat(crypto.decrypt(ModelCryptoService.UNSET)).isEmpty();
        assertThat(crypto.maskForAdmin(ModelCryptoService.UNSET)).isNull();
        assertThatThrownBy(() -> crypto.requireDecrypt(ModelCryptoService.UNSET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSET");
    }

    @Test
    void deriveKey_sha256WhenLengthInvalid() {
        byte[] derived = ModelCryptoService.deriveKey("short-key");
        assertThat(derived).hasSize(32);
        ModelCryptoService shortKey = new ModelCryptoService("short-key");
        String enc = shortKey.encrypt("plain");
        assertThat(shortKey.decrypt(enc)).isEqualTo("plain");
    }

    @Test
    void deriveKey_acceptsBase64Aes256() {
        byte[] raw = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(raw);
        assertThat(ModelCryptoService.deriveKey(b64)).isEqualTo(raw);
    }
}
