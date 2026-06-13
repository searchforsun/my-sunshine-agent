package com.sunshine.testsupport;

import com.github.fppt.jedismock.RedisServer;
import com.sunshine.orchestrator.generation.GenerationProperties;
import com.sunshine.orchestrator.generation.GenerationStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 放在 {@code com.sunshine.testsupport}，避免被 {@code com.sunshine.orchestrator} 组件扫描误加载。
 */
@Slf4j
@TestConfiguration
public class EmbeddedRedisTestConfig {

    private static final int REDIS_PORT = 6370;

    private static final RedisServer REDIS_SERVER;

    static {
        try {
            REDIS_SERVER = RedisServer.newRedisServer(REDIS_PORT).start();
            log.info("Jedis-Mock Redis started at {}:{}", REDIS_SERVER.getHost(), REDIS_SERVER.getBindPort());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to start Jedis-Mock Redis on port " + REDIS_PORT, ex);
        }
    }

    public static String redisHost() {
        return REDIS_SERVER.getHost();
    }

    public static int redisPort() {
        return REDIS_SERVER.getBindPort();
    }

    @Bean(destroyMethod = "stop")
    RedisServer redisServer() {
        return REDIS_SERVER;
    }

    @Bean
    RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(redisConnectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    GenerationStreamService generationStreamService(
            StringRedisTemplate stringRedisTemplate,
            GenerationProperties generationProperties) {
        return new GenerationStreamService(stringRedisTemplate, generationProperties);
    }
}
