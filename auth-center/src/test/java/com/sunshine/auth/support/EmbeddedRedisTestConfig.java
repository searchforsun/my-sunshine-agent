package com.sunshine.auth.support;

import com.github.fppt.jedismock.RedisServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.ServerSocket;

@Slf4j
@TestConfiguration
public class EmbeddedRedisTestConfig {

    private static final RedisServer REDIS_SERVER;

    static {
        try {
            int port = findFreePort();
            REDIS_SERVER = RedisServer.newRedisServer(port).start();
            log.info("Jedis-Mock Redis started at {}:{}", REDIS_SERVER.getHost(), REDIS_SERVER.getBindPort());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to start Jedis-Mock Redis", ex);
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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
            @Value("${spring.data.redis.port}") int port) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }
}
