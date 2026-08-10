package com.sunshine.llm.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCryptoServiceTest {

    private final ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");

    @Test
    void encryptDecrypt_roundTrip() {
        String plain = "sk-test-secret-key";
        String enc = crypto.encrypt(plain);
        assertThat(enc).isNotEqualTo(plain);
        assertThat(crypto.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    void decrypt_unset_throwsClearError() {
        assertThatThrownBy(() -> crypto.decrypt("UNSET"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSET");
        assertThat(crypto.isConfigured("UNSET")).isFalse();
        assertThat(crypto.isConfigured(crypto.encrypt("sk"))).isTrue();
    }

    @Test
    void deriveKey_sha256WhenNotBase64KeyLength() {
        byte[] key = ModelCryptoService.deriveKey("sunshine-model-aes-key-32b!!");
        assertThat(key).hasSize(32);
    }
}
