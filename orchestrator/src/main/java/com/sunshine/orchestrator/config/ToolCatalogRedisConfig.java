package com.sunshine.orchestrator.config;

import com.sunshine.common.model.ModelCatalogChannels;
import com.sunshine.common.skill.SkillCatalogChannels;
import com.sunshine.orchestrator.catalog.SkillCatalogRefreshListener;
import com.sunshine.orchestrator.catalog.ToolCatalogRefreshListener;
import com.sunshine.orchestrator.registry.ModelCatalogRefreshListener;
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
            ToolCatalogRefreshListener toolListener,
            WorkflowCatalogRefreshListener workflowListener,
            ModelCatalogRefreshListener modelListener,
            SkillCatalogRefreshListener skillListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(toolListener, new ChannelTopic("tool-catalog-changed"));
        container.addMessageListener(workflowListener, new ChannelTopic("workflow-catalog-changed"));
        container.addMessageListener(modelListener, new ChannelTopic(ModelCatalogChannels.CHANGED));
        container.addMessageListener(skillListener, new ChannelTopic(SkillCatalogChannels.CHANGED));
        return container;
    }
}
