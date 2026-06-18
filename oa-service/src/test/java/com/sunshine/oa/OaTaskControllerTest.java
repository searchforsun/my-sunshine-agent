package com.sunshine.oa;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.oa.controller.OaTaskController;
import com.sunshine.oa.service.OaTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OaTaskController.class)
@Import({OaTaskService.class, GlobalExceptionHandler.class})
class OaTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listTasks_returnsMockData() throws Exception {
        mockMvc.perform(get("/api/oa/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    @Test
    void listTasks_filtersPending() throws Exception {
        mockMvc.perform(get("/api/oa/tasks").param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(2001));
    }
}
