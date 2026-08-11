package com.sunshine.llm.config;

import com.sunshine.common.model.ModelCatalogChannels;
import com.sunshine.llm.registry.ModelCatalogRefreshListener;
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
        container.addMessageListener(listener, new ChannelTopic(ModelCatalogChannels.CHANGED));
        return container;
    }
}
