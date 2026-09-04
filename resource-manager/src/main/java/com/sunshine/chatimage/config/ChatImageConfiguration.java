package com.sunshine.chatimage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** chat-image 配置属性注册（应用主类未开 ConfigurationPropertiesScan） */
@Configuration
@EnableConfigurationProperties(ChatImageProperties.class)
public class ChatImageConfiguration {
}
