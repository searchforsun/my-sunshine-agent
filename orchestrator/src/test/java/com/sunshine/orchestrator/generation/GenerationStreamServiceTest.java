package com.sunshine.orchestrator.generation;

import com.sunshine.testsupport.EmbeddedRedisTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmbeddedRedisTestConfig.class)
@EnableConfigurationProperties(GenerationProperties.class)
class GenerationStreamServiceTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String USER_ID = "alice";
    private static final String TENANT_ID = "default";
    private static final String INTENT = "chat";

    @Autowired
    private GenerationStreamService streamService;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", EmbeddedRedisTestConfig::redisHost);
        registry.add("spring.data.redis.port", EmbeddedRedisTestConfig::redisPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("agent.generation.ttl-sec", () -> 3600);
        registry.add("agent.generation.orphan-timeout-sec", () -> 120);
        registry.add("agent.generation.max-buffer-chunks", () -> 10000);
        registry.add("agent.generation.reconnect-block-ms", () -> 100);
        registry.add("agent.generation.flush-interval-ms", () -> 50);
    }

    @BeforeEach
    void cleanRedis() {
        Set<String> keys = redis.keys("sunshine:gen:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("create → append 3 chunks → readFrom(afterSeq=1) 返回 seq 2 和 3")
    void readFromAfterSeq_returnsOnlyLaterChunks() {
        String generationId = streamService.createGeneration(
                CONVERSATION_ID, MESSAGE_ID, USER_ID, TENANT_ID, INTENT);

        streamService.appendChunk(generationId, 1, "chunk-1");
        streamService.appendChunk(generationId, 2, "chunk-2");
        streamService.appendChunk(generationId, 3, "chunk-3");

        List<StreamEvent> events = streamService.readFrom(generationId, 1, 10);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).seq()).isEqualTo(2);
        assertThat(events.get(0).text()).isEqualTo("chunk-2");
        assertThat(events.get(1).seq()).isEqualTo(3);
        assertThat(events.get(1).text()).isEqualTo("chunk-3");

        GenerationMeta meta = streamService.getMeta(generationId).orElseThrow();
        assertThat(meta.status()).isEqualTo(GenerationStatus.CREATED);
        assertThat(meta.lastSeq()).isEqualTo(3);
        assertThat(meta.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(meta.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(meta.userId()).isEqualTo(USER_ID);
        assertThat(meta.tenantId()).isEqualTo(TENANT_ID);
        assertThat(meta.intent()).isEqualTo(INTENT);
    }
}
