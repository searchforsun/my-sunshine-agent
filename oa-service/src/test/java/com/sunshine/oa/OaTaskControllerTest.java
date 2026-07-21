package com.sunshine.oa;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.oa.controller.OaTaskController;
import com.sunshine.oa.service.OaBizService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureDataJpa;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OaTaskController.class)
@AutoConfigureDataJpa
@Import({OaBizService.class, GlobalExceptionHandler.class})
@EntityScan("com.sunshine.oa.entity")
@EnableJpaRepositories("com.sunshine.oa.repo")
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/data-oa.sql")
class OaTaskControllerTest {

    static final String ALICE = "a1111111-1111-4111-a111-111111111111";
    static final String BOB = "b2222222-2222-4222-b222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listTasks_requiresUserHeader() throws Exception {
        mockMvc.perform(get("/api/oa/tasks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listTasks_bobSeesOwnPending() throws Exception {
        mockMvc.perform(get("/api/oa/tasks").header("x-user-id", BOB).param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("task-b1"));
    }

    @Test
    void listTasks_aliceDoesNotSeeBobs() throws Exception {
        mockMvc.perform(get("/api/oa/tasks").header("x-user-id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("task-a1"));
    }

    @Test
    void approveTask_bobSucceeds() throws Exception {
        mockMvc.perform(post("/api/oa/tasks/task-b1/approve").header("x-user-id", BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"));
    }

    @Test
    void approveTask_aliceCannotApproveBobs() throws Exception {
        mockMvc.perform(post("/api/oa/tasks/task-b1/approve").header("x-user-id", ALICE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
