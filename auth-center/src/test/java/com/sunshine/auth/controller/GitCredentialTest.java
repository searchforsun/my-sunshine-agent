package com.sunshine.auth.controller;

import com.sunshine.auth.entity.UserEntity;
import com.sunshine.auth.repo.UserRepository;
import com.sunshine.auth.service.UserService;
import com.sunshine.auth.support.EmbeddedRedisTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedRedisTestConfig.class)
class GitCredentialTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", EmbeddedRedisTestConfig::redisHost);
        registry.add("spring.data.redis.port", EmbeddedRedisTestConfig::redisPort);
    }

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    void shouldReturnGithubCredentialWhenHostMatches() {
        UserEntity user = new UserEntity();
        user.setId("u-git-1");
        user.setUsername("gituser1");
        user.setPasswordHash("$2a$10$dummyhashedvalue00000000000000000000000000000000000");
        user.setNickname("Git User");
        user.setTenantId("default");
        user.setStatus((byte) 1);
        user.setGithubUrl("https://github.com");
        user.setGithubToken("ghp_test123");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        Map<String, String> cred = userService.findGitCredential("u-git-1", "github.com");
        assertThat(cred.get("url")).isEqualTo("https://github.com");
        assertThat(cred.get("token")).isEqualTo("ghp_test123");
    }

    @Test
    void shouldReturnEmptyWhenHostNotMatching() {
        UserEntity user = new UserEntity();
        user.setId("u-git-2");
        user.setUsername("gituser2");
        user.setPasswordHash("$2a$10$dummyhashedvalue00000000000000000000000000000000000");
        user.setNickname("Git User 2");
        user.setTenantId("default");
        user.setStatus((byte) 1);
        user.setGithubUrl("https://github.com");
        user.setGithubToken("ghp_test456");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        Map<String, String> cred = userService.findGitCredential("u-git-2", "gitlab.example.com");
        assertThat(cred).isEmpty();
    }

    @Test
    void shouldMatchGitlabCredentialWhenHostMatches() {
        UserEntity user = new UserEntity();
        user.setId("u-git-3");
        user.setUsername("gituser3");
        user.setPasswordHash("$2a$10$dummyhashedvalue00000000000000000000000000000000000");
        user.setNickname("Git User 3");
        user.setTenantId("default");
        user.setStatus((byte) 1);
        user.setGitlabUrl("https://gitlab.example.com");
        user.setGitlabToken("glpat-test789");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        Map<String, String> cred = userService.findGitCredential("u-git-3", "gitlab.example.com");
        assertThat(cred.get("url")).isEqualTo("https://gitlab.example.com");
        assertThat(cred.get("token")).isEqualTo("glpat-test789");
    }
}
