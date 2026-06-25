package com.sunshine.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.auth.entity.UserEntity;
import com.sunshine.auth.repo.UserRepository;
import com.sunshine.auth.support.EmbeddedRedisTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedRedisTestConfig.class)
class AuthControllerTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("注册成功 200")
    void register_success() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice01","password":"password123","nickname":"Alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("alice01"))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                .andExpect(jsonPath("$.data.userId").isNotEmpty());
    }

    @Test
    @DisplayName("重复用户名 409")
    void register_duplicateUsername() throws Exception {
        registerUser("bob001");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob001","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("非法用户名 400")
    void register_invalidUsername() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("登录成功返回 token")
    void login_success() throws Exception {
        registerUser("carol01");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"carol01","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenName").value("Authorization"))
                .andExpect(jsonPath("$.data.username").value("carol01"));
    }

    @Test
    @DisplayName("错误密码 401")
    void login_wrongPassword() throws Exception {
        registerUser("dave001");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"dave001","password":"wrongpass1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("禁用用户 403")
    void login_disabledUser() throws Exception {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername("disabled1");
        user.setPasswordHash(new BCryptPasswordEncoder().encode(PASSWORD));
        user.setNickname("disabled1");
        user.setStatus((byte) 0);
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"disabled1","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("登出后 me 401")
    void logout_thenMeUnauthorized() throws Exception {
        registerUser("eve0001");
        String token = loginAndGetToken("eve0001");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("me 带有效 token 200")
    void me_withToken() throws Exception {
        registerUser("frank01");
        String token = loginAndGetToken("frank01");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("frank01"));
    }

    @Test
    void register_withTenantId_persistsTenant() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"tenant01","password":"password123","tenantId":"tenant-a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        var user = userRepository.findByUsername("tenant01");
        assertThat(user).isPresent();
        assertThat(user.get().getTenantId()).isEqualTo("tenant-a");
    }

    private void registerUser(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"password123"}
                        """.formatted(username)));
    }

    private String loginAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"password123"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("code").asInt()).isEqualTo(200);
        return root.path("data").path("token").asText();
    }
}
