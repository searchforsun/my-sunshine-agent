package com.sunshine.common.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "model.crypto", name = "aes-key")
public class ModelCryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModelCryptoService modelCryptoService(@Value("${model.crypto.aes-key}") String aesKeyMaterial) {
        return new ModelCryptoService(aesKeyMaterial);
    }
}
