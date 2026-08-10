package com.sunshine.llm.config;

import com.sunshine.llm.registry.ModelCatalogRefreshListener;
import com.sunshine.llm.registry.ModelRegistryCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class ModelCatalogRedisConfig {

    @Bean
    RedisMessageListenerContainer modelCatalogRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            ModelCatalogRefreshListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(ModelRegistryCache.CHANNEL));
        return container;
    }
}
