package com.sunshine.oa;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.oa.controller.OaTaskController;
import com.sunshine.oa.store.OaTenantUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OaTaskController.class)
@Import({OaTenantUserStore.class, GlobalExceptionHandler.class})
class OaTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OaTenantUserStore store;

    @BeforeEach
    void reset() {
        store.reset("default");
    }

    @Test
    void listTasks_requiresUserHeader() throws Exception {
        mockMvc.perform(get("/api/oa/tasks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listTasks_bobSeesOwnPending() throws Exception {
        mockMvc.perform(get("/api/oa/tasks").header("x-user-id", "u-bob").param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("task-b1"));
    }

    @Test
    void listTasks_aliceDoesNotSeeBobs() throws Exception {
        mockMvc.perform(get("/api/oa/tasks").header("x-user-id", "u-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("task-a1"));
    }

    @Test
    void approveTask_bobSucceeds() throws Exception {
        mockMvc.perform(post("/api/oa/tasks/task-b1/approve").header("x-user-id", "u-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"));
    }

    @Test
    void approveTask_aliceCannotApproveBobs() throws Exception {
        mockMvc.perform(post("/api/oa/tasks/task-b1/approve").header("x-user-id", "u-alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
