package com.sunshine.orchestrator.config;

import com.sunshine.orchestrator.catalog.ToolCatalogRefreshListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(prefix = "orchestrator.catalog", name = "refresh-enabled", havingValue = "true", matchIfMissing = true)
public class ToolCatalogRedisConfig {

    @Bean
    RedisMessageListenerContainer toolCatalogRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            ToolCatalogRefreshListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic("tool-catalog-changed"));
        return container;
    }
}
