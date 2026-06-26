package com.sunshine.autoconfigure.orchestrator;

import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.generation.GenerationController;
import com.sunshine.orchestrator.generation.GenerationJobFactory;
import com.sunshine.orchestrator.generation.GenerationProperties;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.generation.GenerationStreamService;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Track G beans register after Redis auto-config (component-scanned {@code @ConditionalOnBean} runs too early).
 */
@Configuration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(name = "spring.data.redis.host")
public class GenerationAutoConfiguration {

    @Bean
    GenerationStreamService generationStreamService(
            StringRedisTemplate redis, GenerationProperties properties) {
        return new GenerationStreamService(redis, properties);
    }

    @Bean
    GenerationJobFactory generationJobFactory(
            GenerationStreamService streamService,
            GenerationProperties properties,
            GenerationFlushScheduler flushScheduler,
            MemoryLifecycleService memoryLifecycleService,
            WorkflowPauseService workflowPauseService,
            ExecutionPlanStore executionPlanStore) {
        return new GenerationJobFactory(
                streamService, properties, flushScheduler, memoryLifecycleService,
                workflowPauseService, executionPlanStore);
    }

    @Bean
    GenerationController generationController(
            GenerationStreamService streamService,
            GenerationRegistry registry,
            GenerationFlushScheduler flushScheduler,
            GenerationProperties generationProperties) {
        return new GenerationController(streamService, registry, flushScheduler, generationProperties);
    }
}
