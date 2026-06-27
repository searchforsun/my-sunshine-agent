package com.sunshine.desensitize;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DesensitizeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void scrub_masksPhoneAndIdCard() throws Exception {
        mockMvc.perform(post("/api/desensitize/scrub")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"联系13812345678，身份证110101199001011234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("联系138****5678，身份证110101********1234"));
    }

    @Test
    void scrub_masksAhoCorasickKeywords() throws Exception {
        mockMvc.perform(post("/api/desensitize/scrub")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"该员工月薪12000元，请保密\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("该员工***12000元，请保密"));
    }
}
